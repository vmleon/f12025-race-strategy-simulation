package udp.server;

import java.io.InputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

public class App {

    private record ReceivedPacket(byte[] data, int length, String sender) {}

    public static void main(String[] args) throws Exception {
        Properties config = new Properties();
        try (InputStream is = App.class.getClassLoader().getResourceAsStream("config.properties")) {
            config.load(is);
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

        ConnectionFactory connFactory = new ConnectionFactory(config);
        DbWriter dbWriter = new DbWriter();
        LifecycleDispatcher lifecycle = new LifecycleDispatcher(connFactory, dbWriter);

        RaceState raceState = new RaceState();

        String tcpHost = config.getProperty("tcp.backend.host", "localhost");
        int tcpPort = Integer.parseInt(config.getProperty("tcp.backend.port", "9090"));
        Thread tcpSender = new Thread(new TcpSender(raceState, tcpHost, tcpPort), "tcp-sender");
        tcpSender.setDaemon(true);
        tcpSender.start();

        BlockingQueue<ReceivedPacket> queue = new ArrayBlockingQueue<>(10_000);

        Thread worker = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    ReceivedPacket received = queue.take();
                    PacketHeader header = PacketHeader.parse(received.data(), received.length());

                    if (header != null) {
                        if (header.packetFormat != 2025 || header.gameYear != 25) {
                            System.out.printf("[%s] WARNING: unexpected format=%d year=%d%n",
                                    received.sender(), header.packetFormat, header.gameYear);
                        }

                        tracker.onPacketReceived(header, received.length());

                        switch (header.packetId) {
                            case 1 -> { // Session
                                SessionData session = SessionData.parse(received.data(), received.length());
                                if (session != null) {
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
                                        DbWriter.SectorSnapshot snapshot = SectorTransitionDetector.captureSnapshot(
                                                header.sessionUID, t, carState, historyBuffer, header.frameIdentifier);
                                        snapshots.add(snapshot);
                                        String tierLabel = switch (t.recovered()) {
                                            case 0 -> "PRIMARY";
                                            case 1 -> "TIER1";
                                            case 2 -> "TIER2";
                                            default -> "GAP";
                                        };
                                        System.out.printf("  SECTOR [%s] car=%d sector=%d lap=%d time=%dms%n",
                                                tierLabel, t.carIndex(), t.completedSector(), t.lapNumber(),
                                                snapshot.sectorTimeMs());
                                    }
                                    lifecycle.onSectorSnapshots(snapshots);
                                }
                            }
                            case 3 -> { // Event
                                EventData event = EventData.parse(received.data(), received.length());
                                if (event != null) {
                                    lifecycle.onEvent(header.sessionUID, header.frameIdentifier, event);
                                    // Forward disruptive events to backend via TCP
                                    switch (event.eventCode) {
                                        case "SCAR" -> raceState.queueEvent(
                                                "{\"type\":\"event\",\"event\":\"SCAR\",\"safetyCarType\":"
                                                + event.safetyCarType + ",\"eventType\":" + event.eventType + "}");
                                        case "RTMT" -> raceState.queueEvent(
                                                "{\"type\":\"event\",\"event\":\"RTMT\",\"carIndex\":"
                                                + event.vehicleIdx + "}");
                                        case "COLL" -> raceState.queueEvent(
                                                "{\"type\":\"event\",\"event\":\"COLL\",\"car1\":"
                                                + event.vehicle1Idx + ",\"car2\":" + event.vehicle2Idx + "}");
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
                            default -> {}
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

        System.out.println("UDP Server listening on " + host + ":" + port);
        System.out.println("Buffer size: " + bufferSizeInBytes + " bytes");

        byte[] buffer = new byte[bufferSizeInBytes];

        while (true) {
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            socket.receive(packet);

            String sender = packet.getAddress().getHostAddress() + ":" + packet.getPort();
            int len = packet.getLength();
            byte[] copy = Arrays.copyOf(packet.getData(), len);

            if (!queue.offer(new ReceivedPacket(copy, len, sender))) {
                System.err.println("Queue full, dropping packet from " + sender);
            }
        }
    }
}
