package udp.server;

import java.io.InputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Properties;
import java.util.Random;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Client {

    private static final Logger log = LoggerFactory.getLogger(Client.class);

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

        log.info("Simulating F1 25 telemetry to {}:{}", host, port);
        log.info("Duration: {}s at {}fps ({} frames)", durationSeconds, fps, totalFrames);

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
        log.info("Done. Sent {} packets in {}s ({} pkts/sec)",
                packetsSent, String.format("%.1f", actualDuration), String.format("%.0f", packetsSent / actualDuration));
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

        // Fill payload with realistic synthetic data
        switch (packetId) {
            case 1 -> fillSessionData(buf);
            case 2 -> fillLapData(buf, frameId);
            case 4 -> fillParticipants(buf);
            case 6 -> fillCarTelemetry(buf, frameId);
            case 7 -> fillCarStatus(buf, frameId);
            case 10 -> fillCarDamage(buf, frameId);
        }

        return buf.array();
    }

    private static void fillLapData(ByteBuffer buf, int frameId) {
        Random rng = new Random(frameId);
        for (int car = 0; car < 22; car++) {
            int lapNum = 1 + (frameId / (60 * 90)); // new lap roughly every 90s
            int sector = (frameId / (60 * 30)) % 3; // rotate sectors every ~30s
            long lapTimeMs = (frameId % (60 * 90)) * (1000 / 60);

            buf.putInt((int) (85000 + rng.nextInt(10000)));  // lastLapTimeInMS
            buf.putInt((int) lapTimeMs);                      // currentLapTimeInMS
            buf.putShort((short) (28000 + rng.nextInt(4000))); // sector1TimeMSPart
            buf.put((byte) 0);                                 // sector1TimeMinutesPart
            buf.putShort((short) (30000 + rng.nextInt(4000))); // sector2TimeMSPart
            buf.put((byte) 0);                                 // sector2TimeMinutesPart
            buf.putShort((short) rng.nextInt(2000));           // deltaToCarInFrontMSPart
            buf.put((byte) 0);                                 // deltaToCarInFrontMinutesPart
            buf.putShort((short) rng.nextInt(5000));           // deltaToRaceLeaderMSPart
            buf.put((byte) 0);                                 // deltaToRaceLeaderMinutesPart
            buf.putFloat(rng.nextFloat() * 5000);              // lapDistance
            buf.putFloat(rng.nextFloat() * 50000);             // totalDistance
            buf.putFloat(0.0f);                                // safetyCarDelta
            buf.put((byte) (car + 1));                         // carPosition
            buf.put((byte) lapNum);                            // currentLapNum
            buf.put((byte) 0);                                 // pitStatus
            buf.put((byte) 0);                                 // numPitStops
            buf.put((byte) sector);                            // sector
            buf.put((byte) 0);                                 // currentLapInvalid
            buf.put((byte) 0);                                 // penalties
            buf.put((byte) 0);                                 // totalWarnings
            buf.put((byte) 0);                                 // cornerCuttingWarnings
            buf.put((byte) 0);                                 // numUnservedDriveThroughPens
            buf.put((byte) 0);                                 // numUnservedStopGoPens
            buf.put((byte) (car + 1));                         // gridPosition
            buf.put((byte) 4);                                 // driverStatus (on track)
            buf.put((byte) 2);                                 // resultStatus (active)
            buf.put((byte) 0);                                 // pitLaneTimerActive
            buf.putShort((short) 0);                           // pitLaneTimeInLaneInMS
            buf.putShort((short) 0);                           // pitStopTimerInMS
            buf.put((byte) 0);                                 // pitStopShouldServePen
            buf.putFloat(300.0f + rng.nextFloat() * 30);       // speedTrapFastestSpeed
            buf.put((byte) (1 + rng.nextInt(lapNum)));         // speedTrapFastestLap
        }
    }

    private static void fillCarTelemetry(ByteBuffer buf, int frameId) {
        Random rng = new Random(frameId);
        for (int car = 0; car < 22; car++) {
            buf.putShort((short) (180 + rng.nextInt(160)));    // speed (180-340 kph)
            buf.putFloat(rng.nextFloat());                     // throttle
            buf.putFloat(rng.nextFloat() * 2 - 1);            // steer (-1 to 1)
            buf.putFloat(rng.nextFloat() * 0.3f);             // brake
            buf.put((byte) 0);                                 // clutch
            buf.put((byte) (3 + rng.nextInt(5)));              // gear (3-7)
            buf.putShort((short) (8000 + rng.nextInt(4000)));  // engineRPM
            buf.put((byte) (rng.nextInt(2)));                  // drs
            buf.put((byte) (50 + rng.nextInt(50)));            // revLightsPercent
            buf.putShort((short) 0);                           // revLightsBitValue
            // brakesTemperature[4]
            for (int i = 0; i < 4; i++) buf.putShort((short) (600 + rng.nextInt(400)));
            // tyresSurfaceTemperature[4] (uint8)
            for (int i = 0; i < 4; i++) buf.put((byte) (80 + rng.nextInt(40)));
            // tyresInnerTemperature[4] (uint8)
            for (int i = 0; i < 4; i++) buf.put((byte) (85 + rng.nextInt(40)));
            // engineTemperature
            buf.putShort((short) (100 + rng.nextInt(20)));
            // tyresPressure[4]
            for (int i = 0; i < 4; i++) buf.putFloat(21.0f + rng.nextFloat() * 4);
            // surfaceType[4]
            for (int i = 0; i < 4; i++) buf.put((byte) 0);
        }
    }

    private static void fillSessionData(ByteBuffer buf) {
        buf.put((byte) 0);       // weather (clear)
        buf.put((byte) 28);      // trackTemperature
        buf.put((byte) 22);      // airTemperature
        buf.put((byte) 57);      // totalLaps
        buf.putShort((short) 5303); // trackLength
        buf.put((byte) 10);      // sessionType (race)
        buf.put((byte) 5);       // trackId (Barcelona)
        buf.put((byte) 0);       // formula (F1 Modern)
        // Remaining fields are zero-filled by ByteBuffer.allocate
    }

    private static void fillParticipants(ByteBuffer buf) {
        String[] names = {"Max Verstappen", "Lewis Hamilton", "Charles Leclerc", "Lando Norris",
                "Carlos Sainz", "Oscar Piastri", "George Russell", "Fernando Alonso",
                "Pierre Gasly", "Esteban Ocon", "Lance Stroll", "Yuki Tsunoda",
                "Daniel Ricciardo", "Nico Hulkenberg", "Kevin Magnussen", "Alex Albon",
                "Logan Sargeant", "Valtteri Bottas", "Zhou Guanyu", "Nyck De Vries",
                "AI Driver 21", "AI Driver 22"};
        buf.put((byte) 20); // numActiveCars
        for (int car = 0; car < 22; car++) {
            buf.put((byte) (car == 0 ? 0 : 1)); // aiControlled
            buf.put((byte) car);                  // driverId
            buf.put((byte) 0);                    // networkId
            buf.put((byte) (car % 11));           // teamId
            buf.put((byte) 0);                    // myTeam
            buf.put((byte) (car + 1));            // raceNumber
            buf.put((byte) (car + 1));            // nationality
            byte[] nameBytes = names[car].getBytes(java.nio.charset.StandardCharsets.UTF_8);
            buf.put(nameBytes);
            buf.position(buf.position() + (32 - nameBytes.length)); // pad name
            buf.put((byte) 1);  // yourTelemetry
            buf.put((byte) 1);  // showOnlineNames
            buf.putShort((short) 0); // techLevel
            buf.put((byte) 1);  // platform
            buf.put((byte) 0);  // numColours
            buf.position(buf.position() + 12); // liveryColours
        }
    }

    private static void fillCarStatus(ByteBuffer buf, int frameId) {
        Random rng = new Random(frameId);
        for (int car = 0; car < 22; car++) {
            buf.put((byte) 0);           // tractionControl
            buf.put((byte) 1);           // antiLockBrakes
            buf.put((byte) 1);           // fuelMix
            buf.put((byte) 56);          // frontBrakeBias
            buf.put((byte) 0);           // pitLimiterStatus
            buf.putFloat(40.0f + rng.nextFloat() * 70); // fuelInTank
            buf.putFloat(110.0f);        // fuelCapacity
            buf.putFloat(1.0f + rng.nextFloat() * 3);   // fuelRemainingLaps
            buf.putShort((short) 12500); // maxRPM
            buf.putShort((short) 4000);  // idleRPM
            buf.put((byte) 8);           // maxGears
            buf.put((byte) (rng.nextInt(2))); // drsAllowed
            buf.putShort((short) (rng.nextInt(300))); // drsActivationDistance
            buf.put((byte) (16 + rng.nextInt(5))); // actualTyreCompound
            buf.put((byte) (16 + rng.nextInt(3))); // visualTyreCompound
            buf.put((byte) (rng.nextInt(20)));     // tyresAgeLaps
            buf.put((byte) 0);           // vehicleFIAFlags
            buf.putFloat(500000.0f);     // enginePowerICE
            buf.putFloat(120000.0f);     // enginePowerMGUK
            buf.putFloat(4000000.0f);    // ersStoreEnergy
            buf.put((byte) (rng.nextInt(4))); // ersDeployMode
            buf.putFloat(100000.0f);     // ersHarvestedThisLapMGUK
            buf.putFloat(200000.0f);     // ersHarvestedThisLapMGUH
            buf.putFloat(150000.0f);     // ersDeployedThisLap
            buf.put((byte) 0);           // networkPaused
        }
    }

    private static void fillCarDamage(ByteBuffer buf, int frameId) {
        Random rng = new Random(frameId);
        for (int car = 0; car < 22; car++) {
            // tyresWear[4]
            for (int i = 0; i < 4; i++) buf.putFloat(rng.nextFloat() * 30);
            // tyresDamage[4]
            for (int i = 0; i < 4; i++) buf.put((byte) rng.nextInt(10));
            // brakesDamage[4]
            for (int i = 0; i < 4; i++) buf.put((byte) rng.nextInt(5));
            // tyreBlisters[4]
            for (int i = 0; i < 4; i++) buf.put((byte) rng.nextInt(20));
            buf.put((byte) rng.nextInt(10)); // frontLeftWingDamage
            buf.put((byte) rng.nextInt(10)); // frontRightWingDamage
            buf.put((byte) 0);              // rearWingDamage
            buf.put((byte) 0);              // floorDamage
            buf.put((byte) 0);              // diffuserDamage
            buf.put((byte) 0);              // sidepodDamage
            buf.put((byte) 0);              // drsFault
            buf.put((byte) 0);              // ersFault
            buf.put((byte) rng.nextInt(5)); // gearBoxDamage
            buf.put((byte) rng.nextInt(5)); // engineDamage
            // engine component wear
            for (int i = 0; i < 6; i++) buf.put((byte) rng.nextInt(20));
            buf.put((byte) 0); // engineBlown
            buf.put((byte) 0); // engineSeized
        }
    }
}
