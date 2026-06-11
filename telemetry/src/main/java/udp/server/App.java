package udp.server;

import java.io.IOException;
import java.io.InputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

public class App {

    private static final Logger log = LoggerFactory.getLogger(App.class);

    private record ReceivedPacket(byte[] data, int length, String sender) {}

    public static void main(String[] args) throws Exception {
        Properties config = new Properties();
        try (InputStream is = App.class.getClassLoader().getResourceAsStream("config.properties")) {
            config.load(is);
        }

        String envOracleHost = System.getenv("ORACLE_HOST");
        if (envOracleHost != null && !envOracleHost.isEmpty()) {
            String envOraclePort = System.getenv().getOrDefault("ORACLE_PORT", "1521");
            String dbUrl = config.getProperty("db.url", "jdbc:oracle:thin:@localhost:1521/FREEPDB1");
            int slashIdx = dbUrl.lastIndexOf('/');
            String service = slashIdx > 0 ? dbUrl.substring(slashIdx + 1) : "FREEPDB1";
            config.setProperty("db.url", "jdbc:oracle:thin:@" + envOracleHost + ":" + envOraclePort + "/" + service);
        }
        String envTcpHost = System.getenv("TCP_BACKEND_HOST");
        if (envTcpHost != null && !envTcpHost.isEmpty()) {
            config.setProperty("tcp.backend.host", envTcpHost);
        }
        String envTcpPort = System.getenv("TCP_BACKEND_PORT");
        if (envTcpPort != null && !envTcpPort.isEmpty()) {
            config.setProperty("tcp.backend.port", envTcpPort);
        }

        String host = config.getProperty("host", "0.0.0.0");
        int port = Integer.parseInt(config.getProperty("port", "20777"));
        int bufferSizeInBytes = Integer.parseInt(config.getProperty("bufferSizeInBytes", "2048"));

        InetAddress address = InetAddress.getByName(host);
        DatagramSocket socket = new DatagramSocket(port, address);

        PacketTracker tracker = new InMemoryPacketTracker();
        CarStateTracker carState = new CarStateTracker();
        SectorTransitionDetector sectorDetector = new SectorTransitionDetector();
        SessionHistoryBuffer historyBuffer = new SessionHistoryBuffer();
        DrivingEventDetector drivingDetector = new DrivingEventDetector();

        ConnectionFactory connFactory = new ConnectionFactory(config);
        DbWriter dbWriter = new DbWriter();
        LifecycleDispatcher lifecycle = new LifecycleDispatcher(connFactory, dbWriter);

        RaceState raceState = new RaceState();

        String tcpHost = config.getProperty("tcp.backend.host", "localhost");
        int tcpPort = Integer.parseInt(config.getProperty("tcp.backend.port", "9090"));
        Thread tcpSender = new Thread(new TcpSender(raceState, tcpHost, tcpPort), "tcp-sender");
        tcpSender.setDaemon(true);
        tcpSender.start();

        Path heartbeatPath = Paths.get("/tmp/heartbeat");
        ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "heartbeat");
            t.setDaemon(true);
            return t;
        });
        heartbeat.scheduleAtFixedRate(() -> {
            try {
                Files.writeString(heartbeatPath, Long.toString(System.currentTimeMillis()));
            } catch (IOException e) {
                log.warn("heartbeat write failed: {}", e.getMessage());
            }
        }, 0, 30, TimeUnit.SECONDS);

        BlockingQueue<ReceivedPacket> queue = new ArrayBlockingQueue<>(10_000);

        Thread worker = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    ReceivedPacket received = queue.take();
                    PacketHeader header = PacketHeader.parse(received.data(), received.length());

                    if (header != null) {
                        MDC.put("sessionUid", String.valueOf(header.sessionUID));
                        try {
                        if (header.packetFormat != 2025 || header.gameYear != 25) {
                            log.warn("[{}] unexpected format={} year={}", received.sender(), header.packetFormat, header.gameYear);
                        }

                        tracker.onPacketReceived(header, received.length());

                        switch (header.packetId) {
                            case 1 -> { // Session
                                SessionData session = SessionData.parse(received.data(), received.length());
                                if (session != null) {
                                    if (!lifecycle.getSeenSessions().contains(header.sessionUID)) {
                                        drivingDetector.reset();
                                    }
                                    carState.updateSession(session);
                                    lifecycle.onSession(header.sessionUID, session);
                                    raceState.updateFromSession(header.sessionUID, session);
                                }
                            }
                            case 2 -> { // LapData
                                LapData[] laps = LapData.parseAll(received.data(), received.length());
                                if (laps != null) {
                                    carState.updateLapData(laps);
                                    raceState.updateFromLapData(laps);
                                    lifecycle.detectPitStops(laps);
                                    var transitions = sectorDetector.detect(laps, historyBuffer);
                                    List<DbWriter.SectorSnapshot> snapshots = new ArrayList<>();
                                    for (var t : transitions) {
                                        if (laps[t.carIndex()].resultStatus <= 1) continue;
                                        DbWriter.SectorSnapshot snapshot = SectorTransitionDetector.captureSnapshot(
                                                header.sessionUID, t, carState, historyBuffer, header.frameIdentifier);
                                        snapshots.add(snapshot);
                                        String tierLabel = switch (t.recovered()) {
                                            case 0 -> "PRIMARY";
                                            case 1 -> "TIER1";
                                            case 2 -> "TIER2";
                                            default -> "GAP";
                                        };
                                        log.info("SECTOR [{}] car={} sector={} lap={} time={}ms",
                                                tierLabel, t.carIndex(), t.completedSector(), t.lapNumber(),
                                                snapshot.sectorTimeMs());
                                    }
                                    lifecycle.onSectorSnapshots(header.sessionUID, snapshots);
                                }
                            }
                            case 3 -> { // Event
                                EventData event = EventData.parse(received.data(), received.length());
                                if (event != null) {
                                    if ("FLBK".equals(event.eventCode)) {
                                        lifecycle.onFlashback(header.sessionUID, header.frameIdentifier, event);
                                        drivingDetector.reset();
                                    } else {
                                        lifecycle.onEvent(header.sessionUID, header.frameIdentifier, event);
                                    }
                                    // Forward disruptive events to backend via TCP
                                    switch (event.eventCode) {
                                        case "SCAR" -> raceState.queueEvent(
                                                "{\"type\":\"event\",\"event\":\"SCAR\",\"safetyCarType\":"
                                                + event.safetyCarType + ",\"eventType\":" + event.eventType + "}");
                                        case "RTMT" -> raceState.queueEvent(
                                                "{\"type\":\"event\",\"event\":\"RTMT\",\"carIndex\":"
                                                + event.vehicleIdx + ",\"driverName\":\""
                                                + raceState.driverNameJson(event.vehicleIdx) + "\"}");
                                        case "COLL" -> raceState.queueEvent(
                                                "{\"type\":\"event\",\"event\":\"COLL\",\"car1\":"
                                                + event.vehicle1Idx + ",\"car2\":" + event.vehicle2Idx + "}");
                                        case "PENA" -> raceState.queueEvent(
                                                "{\"type\":\"event\",\"event\":\"PENA\",\"carIndex\":"
                                                + event.vehicleIdx + ",\"penaltyType\":" + event.penaltyType
                                                + ",\"infringementType\":" + event.infringementType
                                                + ",\"time\":" + event.time + ",\"lap\":" + event.lapNum + "}");
                                        case "CHQF" -> raceState.queueEvent(
                                                "{\"type\":\"event\",\"event\":\"CHQF\"}");
                                        case "FLBK" -> raceState.queueEvent(
                                                "{\"type\":\"event\",\"event\":\"FLBK\"}");
                                        default -> {}
                                    }
                                }
                            }
                            case 4 -> { // Participants
                                ParticipantData[] participants = ParticipantData.parseAll(received.data(), received.length());
                                if (participants != null) {
                                    lifecycle.onParticipants(header.sessionUID, participants);
                                    raceState.updateFromParticipants(participants);
                                }
                            }
                            case 6 -> { // CarTelemetry
                                CarTelemetryData[] telemetry = CarTelemetryData.parseAll(received.data(), received.length());
                                if (telemetry != null) {
                                    carState.updateTelemetry(telemetry);
                                    raceState.updateFromTelemetry(telemetry);
                                }
                            }
                            case 7 -> { // CarStatus
                                CarStatusData[] status = CarStatusData.parseAll(received.data(), received.length());
                                if (status != null) {
                                    carState.updateStatus(status);
                                    raceState.updateFromStatus(status);
                                }
                            }
                            case 10 -> { // CarDamage
                                CarDamageData[] damage = CarDamageData.parseAll(received.data(), received.length());
                                if (damage != null) {
                                    carState.updateDamage(damage);
                                    raceState.updateFromDamage(damage);
                                }
                            }
                            case 8 -> { // FinalClassification
                                FinalClassificationData[] classifications = FinalClassificationData.parseAll(received.data(), received.length());
                                if (classifications != null) {
                                    lifecycle.onFinalClassification(header.sessionUID, classifications);
                                    raceState.markSessionEnded();
                                }
                            }
                            case 11 -> { // SessionHistory
                                SessionHistoryData history = SessionHistoryData.parse(received.data(), received.length());
                                if (history != null) {
                                    historyBuffer.update(history);
                                }
                            }
                            case 12 -> { // TyreSets
                                TyreSetData.TyreSetPacket tyrePacket = TyreSetData.parse(received.data(), received.length());
                                if (tyrePacket != null) {
                                    lifecycle.onTyreSets(header.sessionUID, tyrePacket);
                                }
                            }
                            case 13 -> { // MotionEx (player car only)
                                MotionExData motion = MotionExData.parse(received.data(), received.length());
                                int playerIdx = header.playerCarIndex;
                                CarTelemetryData tel = carState.getTelemetry(playerIdx);
                                LapData lap = carState.getLapData(playerIdx);
                                SessionData session = carState.getSession();
                                if (motion != null && tel != null && lap != null && session != null) {
                                    DrivingEventDetector.FrameInput in = new DrivingEventDetector.FrameInput(
                                            header.sessionTime * 1000.0, lap.lapDistance, lap.currentLapNum,
                                            lap.sector, header.frameIdentifier,
                                            tel.brake, tel.throttle, tel.steer,
                                            motion.localVelocityX, motion.localVelocityY, motion.chassisYaw,
                                            motion.wheelSpeed, motion.wheelSlipRatio, motion.wheelSlipAngle,
                                            tel.speed);
                                    List<DrivingEvent> detected = drivingDetector.onFrame(in);
                                    if (!detected.isEmpty()) {
                                        List<DbWriter.DrivingEventRow> rows = new ArrayList<>(detected.size());
                                        for (DrivingEvent e : detected) {
                                            rows.add(new DbWriter.DrivingEventRow(
                                                    header.sessionUID, playerIdx, session.trackId, session.sessionType,
                                                    e.lapNumber(), e.sectorNumber(), e.lapDistanceStartM(), e.lapDistanceEndM(),
                                                    e.eventType(), e.locationDetail(), e.peakIntensity(), e.intensitySignal(),
                                                    e.durationMs(), e.entrySpeedKmh(),
                                                    e.brakePeak(), e.throttlePeak(), e.steerAbsPeak(),
                                                    e.brakeAtPeak(), e.throttleAtPeak(), e.steerAtPeak(),
                                                    e.frameIdentifier()));
                                            log.info("DRIVING_EVENT {} sector={} dist={} detail={} peak={}",
                                                    e.eventType(), e.sectorNumber(), e.lapDistanceStartM(),
                                                    e.locationDetail(), e.peakIntensity());
                                        }
                                        lifecycle.onDrivingEvents(header.sessionUID, rows);
                                    }
                                }
                            }
                            default -> {}
                        }
                        } finally {
                            MDC.remove("sessionUid");
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }, "packet-processor");
        worker.setDaemon(true);
        worker.start();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            tracker.printSummary();
            socket.close();
        }));

        log.info("UDP Server listening on {}:{}", host, port);
        for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
            if (ni.isLoopback() || !ni.isUp()) continue;
            for (InetAddress addr : Collections.list(ni.getInetAddresses())) {
                if (addr.getAddress().length == 4) { // IPv4 only
                    log.info("Telemetry listening at {}:{}", addr.getHostAddress(), port);
                }
            }
        }
        log.info("Buffer size: {} bytes", bufferSizeInBytes);

        // Database connectivity check
        try (Connection conn = connFactory.getConnection()) {
            log.info("Database: OK ({})", conn.getMetaData().getURL());
        } catch (Exception e) {
            log.error("Database: FAILED ({})", e.getMessage(), e);
            Throwable cause = e.getCause();
            while (cause != null) {
                log.error("  Caused by: {}", cause.getMessage());
                cause = cause.getCause();
            }
        }

        byte[] buffer = new byte[bufferSizeInBytes];

        while (true) {
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            socket.receive(packet);

            String sender = packet.getAddress().getHostAddress() + ":" + packet.getPort();
            int len = packet.getLength();
            byte[] copy = Arrays.copyOf(packet.getData(), len);

            if (!queue.offer(new ReceivedPacket(copy, len, sender))) {
                log.warn("Queue full, dropping packet from {}", sender);
            }
        }
    }
}
