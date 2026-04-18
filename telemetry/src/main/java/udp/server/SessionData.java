package udp.server;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Session-level data from PacketSessionData (packetId=1).
 * Extracts only the fields needed for sector snapshots and database writes.
 */
public class SessionData {

    private static final int MARSHAL_ZONE_SIZE = 5;   // float + int8
    private static final int MAX_MARSHAL_ZONES = 21;
    private static final int WEATHER_SAMPLE_SIZE = 8;
    private static final int MAX_WEATHER_SAMPLES = 64;
    private static final int MAX_SESSIONS_IN_WEEKEND = 12;

    public final int weather;              // uint8
    public final int trackTemperature;     // int8
    public final int airTemperature;       // int8
    public final int totalLaps;            // uint8
    public final int trackLength;          // uint16
    public final int sessionType;          // uint8
    public final int trackId;              // int8
    public final int formula;              // uint8
    public final int sessionTimeLeft;      // uint16 (seconds)
    public final int sessionDuration;      // uint16 (seconds)
    public final int safetyCarStatus;      // uint8
    public final int aiDifficulty;         // uint8
    public final int ersAssist;            // uint8
    public final int drsAssist;            // uint8
    public final int lowFuelMode;          // uint8
    public final int carDamage;            // uint8
    public final int carDamageRate;        // uint8
    public final int safetyCar;            // uint8
    public final float sector2LapDistanceStart;
    public final float sector3LapDistanceStart;

    // Weather forecast
    public final int numWeatherForecastSamples;
    public final WeatherForecastSample[] weatherForecastSamples;

    public static class WeatherForecastSample {
        public final int sessionType;          // uint8
        public final int timeOffset;           // uint8 (minutes)
        public final int weather;              // uint8
        public final int trackTemperature;     // int8
        public final int trackTemperatureChange; // int8
        public final int airTemperature;       // int8
        public final int airTemperatureChange; // int8
        public final int rainPercentage;       // uint8

        WeatherForecastSample(ByteBuffer buf) {
            this.sessionType = Byte.toUnsignedInt(buf.get());
            this.timeOffset = Byte.toUnsignedInt(buf.get());
            this.weather = Byte.toUnsignedInt(buf.get());
            this.trackTemperature = buf.get();
            this.trackTemperatureChange = buf.get();
            this.airTemperature = buf.get();
            this.airTemperatureChange = buf.get();
            this.rainPercentage = Byte.toUnsignedInt(buf.get());
        }
    }

    private SessionData(ByteBuffer buf) {
        this.weather = Byte.toUnsignedInt(buf.get());
        this.trackTemperature = buf.get(); // signed
        this.airTemperature = buf.get();   // signed
        this.totalLaps = Byte.toUnsignedInt(buf.get());
        this.trackLength = Short.toUnsignedInt(buf.getShort());
        this.sessionType = Byte.toUnsignedInt(buf.get());
        this.trackId = buf.get(); // signed
        this.formula = Byte.toUnsignedInt(buf.get());
        this.sessionTimeLeft = Short.toUnsignedInt(buf.getShort());
        this.sessionDuration = Short.toUnsignedInt(buf.getShort());
        buf.get();      // pitSpeedLimit
        buf.get();      // gamePaused
        buf.get();      // isSpectating
        buf.get();      // spectatorCarIndex
        buf.get();      // sliProNativeSupport
        buf.get();      // numMarshalZones
        buf.position(buf.position() + MAX_MARSHAL_ZONES * MARSHAL_ZONE_SIZE); // skip marshal zones
        this.safetyCarStatus = Byte.toUnsignedInt(buf.get());
        buf.get(); // networkGame
        this.numWeatherForecastSamples = Byte.toUnsignedInt(buf.get());
        this.weatherForecastSamples = new WeatherForecastSample[numWeatherForecastSamples];
        for (int i = 0; i < numWeatherForecastSamples; i++) {
            weatherForecastSamples[i] = new WeatherForecastSample(buf);
        }
        // Skip remaining unused forecast slots
        buf.position(buf.position() + (MAX_WEATHER_SAMPLES - numWeatherForecastSamples) * WEATHER_SAMPLE_SIZE);
        buf.get(); // forecastAccuracy
        this.aiDifficulty = Byte.toUnsignedInt(buf.get());
        buf.getInt(); // seasonLinkIdentifier
        buf.getInt(); // weekendLinkIdentifier
        buf.getInt(); // sessionLinkIdentifier
        buf.get(); // pitStopWindowIdealLap
        buf.get(); // pitStopWindowLatestLap
        buf.get(); // pitStopRejoinPosition
        buf.get(); // steeringAssist
        buf.get(); // brakingAssist
        buf.get(); // gearboxAssist
        buf.get(); // pitAssist
        buf.get(); // pitReleaseAssist
        this.ersAssist = Byte.toUnsignedInt(buf.get());
        this.drsAssist = Byte.toUnsignedInt(buf.get());
        buf.get(); // dynamicRacingLine
        buf.get(); // dynamicRacingLineType
        buf.get(); // gameMode
        buf.get(); // ruleSet
        buf.getInt(); // timeOfDay
        buf.get(); // sessionLength
        buf.get(); // speedUnitsLeadPlayer
        buf.get(); // temperatureUnitsLeadPlayer
        buf.get(); // speedUnitsSecondaryPlayer
        buf.get(); // temperatureUnitsSecondaryPlayer
        buf.get(); // numSafetyCarPeriods
        buf.get(); // numVirtualSafetyCarPeriods
        buf.get(); // numRedFlagPeriods
        buf.get(); // equalCarPerformance
        buf.get(); // recoveryMode
        buf.get(); // flashbackLimit
        buf.get(); // surfaceType
        this.lowFuelMode = Byte.toUnsignedInt(buf.get());
        buf.get(); // raceStarts
        buf.get(); // tyreTemperature
        buf.get(); // pitLaneTyreSim
        this.carDamage = Byte.toUnsignedInt(buf.get());
        this.carDamageRate = Byte.toUnsignedInt(buf.get());
        buf.get(); // collisions
        buf.get(); // collisionsOffForFirstLapOnly
        buf.get(); // mpUnsafePitRelease
        buf.get(); // mpOffForGriefing
        buf.get(); // cornerCuttingStringency
        buf.get(); // parcFermeRules
        buf.get(); // pitStopExperience
        this.safetyCar = Byte.toUnsignedInt(buf.get());
        buf.get(); // safetyCarExperience
        buf.get(); // formationLap
        buf.get(); // formationLapExperience
        buf.get(); // redFlags
        buf.get(); // affectsLicenceLevelSolo
        buf.get(); // affectsLicenceLevelMP
        buf.get(); // numSessionsInWeekend
        buf.position(buf.position() + MAX_SESSIONS_IN_WEEKEND); // skip weekendStructure
        this.sector2LapDistanceStart = buf.getFloat();
        this.sector3LapDistanceStart = buf.getFloat();
    }

    /**
     * Parse SessionData from a raw packet buffer. Returns null if too small.
     */
    public static SessionData parse(byte[] data, int length) {
        if (length < 753) {
            return null;
        }
        ByteBuffer buf = ByteBuffer.wrap(data, 0, length);
        buf.order(ByteOrder.LITTLE_ENDIAN);
        buf.position(PacketHeader.HEADER_SIZE);
        return new SessionData(buf);
    }
}
