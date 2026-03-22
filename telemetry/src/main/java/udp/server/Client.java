package udp.server;

import java.io.InputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Properties;
import java.util.Random;

public class Client {

    // Packet sizes from F1 25 spec (includes 29-byte header)
    private static final int[] PACKET_SIZES = {
        1349,  // 0  Motion
        753,   // 1  Session
        1285,  // 2  LapData
        45,    // 3  Event
        1284,  // 4  Participants
        1133,  // 5  CarSetups
        1352,  // 6  CarTelemetry
        1239,  // 7  CarStatus
        1042,  // 8  FinalClassification
        954,   // 9  LobbyInfo
        1041,  // 10 CarDamage
        1460,  // 11 SessionHistory
        231,   // 12 TyreSets
        273,   // 13 MotionEx
        101,   // 14 TimeTrial
        1131,  // 15 LapPositions
    };

    // Send frequency in frames: 1 = every frame, 120 = every 2s at 60fps, etc.
    // 0 = don't send (or special handling)
    private static final int[] FRAME_INTERVAL = {
        1,     // 0  Motion        - every frame
        120,   // 1  Session       - every ~2s
        1,     // 2  LapData       - every frame
        0,     // 3  Event         - sporadic
        300,   // 4  Participants  - every ~5s
        300,   // 5  CarSetups     - every ~5s
        1,     // 6  CarTelemetry  - every frame
        1,     // 7  CarStatus     - every frame
        0,     // 8  FinalClassification - not during race
        0,     // 9  LobbyInfo     - not during race
        120,   // 10 CarDamage     - every ~2s
        120,   // 11 SessionHistory- every ~2s
        120,   // 12 TyreSets      - every ~2s
        1,     // 13 MotionEx      - every frame
        0,     // 14 TimeTrial     - not in race mode
        60,    // 15 LapPositions  - every ~1s
    };

    public static void main(String[] args) throws Exception {
        Properties config = new Properties();
        try (InputStream is = Client.class.getClassLoader().getResourceAsStream("client.properties")) {
            config.load(is);
        }

        String host = config.getProperty("host", "localhost");
        int port = Integer.parseInt(config.getProperty("port", "20777"));
        int durationSeconds = Integer.parseInt(config.getProperty("durationSeconds", "5"));
        int fps = Integer.parseInt(config.getProperty("fps", "60"));

        int totalFrames = durationSeconds * fps;
        long frameDurationNanos = 1_000_000_000L / fps;

        System.out.println("Simulating F1 25 telemetry to " + host + ":" + port);
        System.out.printf("Duration: %ds at %dfps (%d frames)%n", durationSeconds, fps, totalFrames);

        InetAddress address = InetAddress.getByName(host);
        DatagramSocket socket = new DatagramSocket();
        Random random = new Random();
        long sessionUID = random.nextLong();
        int packetsSent = 0;

        long startTime = System.nanoTime();

        for (int frame = 1; frame <= totalFrames; frame++) {
            long frameStart = System.nanoTime();

            for (int typeId = 0; typeId < 16; typeId++) {
                int interval = FRAME_INTERVAL[typeId];
                if (interval == 0) continue;
                if (frame % interval != 0) continue;

                byte[] data = buildF1Packet(typeId, frame, sessionUID, PACKET_SIZES[typeId]);
                DatagramPacket packet = new DatagramPacket(data, data.length, address, port);
                socket.send(packet);
                packetsSent++;
            }

            // Sporadic events: ~2% chance per frame
            if (random.nextInt(100) < 2) {
                byte[] data = buildF1Packet(3, frame, sessionUID, PACKET_SIZES[3]);
                DatagramPacket packet = new DatagramPacket(data, data.length, address, port);
                socket.send(packet);
                packetsSent++;
            }

            // Sleep to maintain target framerate
            long elapsed = System.nanoTime() - frameStart;
            long sleepNanos = frameDurationNanos - elapsed;
            if (sleepNanos > 0) {
                Thread.sleep(sleepNanos / 1_000_000, (int) (sleepNanos % 1_000_000));
            }
        }

        double actualDuration = (System.nanoTime() - startTime) / 1_000_000_000.0;
        System.out.printf("Done. Sent %d packets in %.1fs (%.0f pkts/sec)%n",
                packetsSent, actualDuration, packetsSent / actualDuration);
        socket.close();
    }

    private static byte[] buildF1Packet(int packetId, int frameId, long sessionUID, int totalSize) {
        ByteBuffer buf = ByteBuffer.allocate(totalSize);
        buf.order(ByteOrder.LITTLE_ENDIAN);

        // Header (29 bytes)
        buf.putShort((short) 2025);       // packetFormat
        buf.put((byte) 25);               // gameYear
        buf.put((byte) 1);                // gameMajorVersion
        buf.put((byte) 0);                // gameMinorVersion
        buf.put((byte) 1);                // packetVersion
        buf.put((byte) packetId);          // packetId
        buf.putLong(sessionUID);           // sessionUID
        buf.putFloat(frameId / 60.0f);     // sessionTime (seconds)
        buf.putInt(frameId);               // frameIdentifier
        buf.putInt(frameId);               // overallFrameIdentifier
        buf.put((byte) 0);                // playerCarIndex
        buf.put((byte) 255);              // secondaryPlayerCarIndex

        // Rest is zero-filled (body not implemented yet)
        return buf.array();
    }
}
