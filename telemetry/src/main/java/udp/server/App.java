package udp.server;

import java.io.InputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.Arrays;
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
                                }
                            }
                            case 2 -> { // LapData
                                LapData[] laps = LapData.parseAll(received.data(), received.length());
                                if (laps != null) {
                                    carState.updateLapData(laps);
                                    var transitions = sectorDetector.detect(laps, historyBuffer);
                                    for (var t : transitions) {
                                        DbWriter.SectorSnapshot snapshot = SectorTransitionDetector.captureSnapshot(
                                                header.sessionUID, t, carState, historyBuffer, header.frameIdentifier);
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
                                }
                            }
                            case 3 -> { // Event
                                EventData.parse(received.data(), received.length());
                            }
                            case 4 -> { // Participants
                                ParticipantData.parseAll(received.data(), received.length());
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
                                }
                            }
                            case 10 -> { // CarDamage
                                CarDamageData[] damage = CarDamageData.parseAll(received.data(), received.length());
                                if (damage != null) {
                                    carState.updateDamage(damage);
                                }
                            }
                            case 11 -> { // SessionHistory
                                SessionHistoryData history = SessionHistoryData.parse(received.data(), received.length());
                                if (history != null) {
                                    historyBuffer.update(history);
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
