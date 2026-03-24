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

                        System.out.printf("[%s] size=%d type=%s(%d) sessionUID=0x%X frame=%d%n",
                                received.sender(), received.length(),
                                header.packetTypeName(), header.packetId,
                                header.sessionUID, header.frameIdentifier);

                        switch (header.packetId) {
                            case 1 -> { // Session
                                SessionData session = SessionData.parse(received.data(), received.length());
                                if (session != null) {
                                    System.out.printf("  Session: track=%d weather=%d trackTemp=%d airTemp=%d laps=%d safety=%d%n",
                                            session.trackId, session.weather, session.trackTemperature,
                                            session.airTemperature, session.totalLaps, session.safetyCarStatus);
                                }
                            }
                            case 2 -> { // LapData
                                LapData[] laps = LapData.parseAll(received.data(), received.length());
                                if (laps != null) {
                                    LapData player = laps[header.playerCarIndex];
                                    System.out.printf("  LapData[P%d]: lap=%d sector=%d lapTime=%dms pitStatus=%d driverStatus=%d%n",
                                            header.playerCarIndex, player.currentLapNum, player.sector,
                                            player.currentLapTimeInMS, player.pitStatus, player.driverStatus);
                                }
                            }
                            case 3 -> { // Event
                                EventData event = EventData.parse(received.data(), received.length());
                                if (event != null) {
                                    System.out.printf("  Event: %s vehicleIdx=%d%n", event.eventCode, event.vehicleIdx);
                                }
                            }
                            case 4 -> { // Participants
                                ParticipantData[] parts = ParticipantData.parseAll(received.data(), received.length());
                                if (parts != null) {
                                    int active = ParticipantData.parseNumActiveCars(received.data(), received.length());
                                    System.out.printf("  Participants: %d active, P0=%s (ai=%d)%n",
                                            active, parts[0].name, parts[0].aiControlled);
                                }
                            }
                            case 6 -> { // CarTelemetry
                                CarTelemetryData[] telemetry = CarTelemetryData.parseAll(received.data(), received.length());
                                if (telemetry != null) {
                                    CarTelemetryData player = telemetry[header.playerCarIndex];
                                    System.out.printf("  Telemetry[P%d]: speed=%dkph gear=%d rpm=%d tyreSurf=[%d,%d,%d,%d]%n",
                                            header.playerCarIndex, player.speed, player.gear, player.engineRPM,
                                            player.tyresSurfaceTemperature[0], player.tyresSurfaceTemperature[1],
                                            player.tyresSurfaceTemperature[2], player.tyresSurfaceTemperature[3]);
                                }
                            }
                            case 7 -> { // CarStatus
                                CarStatusData[] status = CarStatusData.parseAll(received.data(), received.length());
                                if (status != null) {
                                    CarStatusData player = status[header.playerCarIndex];
                                    System.out.printf("  Status[P%d]: fuel=%.1fkg compound=%d tyreAge=%d ers=%d drs=%d%n",
                                            header.playerCarIndex, player.fuelInTank, player.actualTyreCompound,
                                            player.tyresAgeLaps, player.ersDeployMode, player.drsAllowed);
                                }
                            }
                            case 10 -> { // CarDamage
                                CarDamageData[] damage = CarDamageData.parseAll(received.data(), received.length());
                                if (damage != null) {
                                    CarDamageData player = damage[header.playerCarIndex];
                                    System.out.printf("  Damage[P%d]: tyreWear=[%.1f,%.1f,%.1f,%.1f] engine=%d gearbox=%d%n",
                                            header.playerCarIndex, player.tyresWear[0], player.tyresWear[1],
                                            player.tyresWear[2], player.tyresWear[3],
                                            player.engineDamage, player.gearBoxDamage);
                                }
                            }
                            default -> {} // Other packet types not yet handled
                        }
                    } else {
                        System.out.printf("[%s] size=%d (too small for F1 header)%n",
                                received.sender(), received.length());
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
