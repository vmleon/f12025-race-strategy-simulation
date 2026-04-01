package dev.victormartin.telemetry;

import java.util.Map;

public final class GameMappings {

    private static final Map<Integer, String> TRACKS = Map.ofEntries(
            Map.entry(0, "Melbourne"),
            Map.entry(1, "Paul Ricard"),
            Map.entry(2, "Shanghai"),
            Map.entry(3, "Bahrain"),
            Map.entry(4, "Catalunya"),
            Map.entry(5, "Monaco"),
            Map.entry(6, "Montreal"),
            Map.entry(7, "Silverstone"),
            Map.entry(8, "Hockenheim"),
            Map.entry(9, "Hungaroring"),
            Map.entry(10, "Spa"),
            Map.entry(11, "Monza"),
            Map.entry(12, "Singapore"),
            Map.entry(13, "Suzuka"),
            Map.entry(14, "Abu Dhabi"),
            Map.entry(15, "Austin"),
            Map.entry(16, "Interlagos"),
            Map.entry(17, "Red Bull Ring"),
            Map.entry(18, "Sochi"),
            Map.entry(19, "Mexico City"),
            Map.entry(20, "Baku"),
            Map.entry(21, "Sakhir Short"),
            Map.entry(22, "Silverstone Short"),
            Map.entry(23, "Austin Short"),
            Map.entry(24, "Suzuka Short"),
            Map.entry(25, "Hanoi"),
            Map.entry(26, "Zandvoort"),
            Map.entry(27, "Imola"),
            Map.entry(28, "Portimao"),
            Map.entry(29, "Jeddah"),
            Map.entry(30, "Miami"),
            Map.entry(31, "Las Vegas"),
            Map.entry(32, "Losail"),
            Map.entry(33, "Lusail")
    );

    private static final Map<Integer, String> SESSION_TYPES = Map.ofEntries(
            Map.entry(0, "Unknown"),
            Map.entry(1, "Practice 1"),
            Map.entry(2, "Practice 2"),
            Map.entry(3, "Practice 3"),
            Map.entry(4, "Short Practice"),
            Map.entry(5, "Qualifying 1"),
            Map.entry(6, "Qualifying 2"),
            Map.entry(7, "Qualifying 3"),
            Map.entry(8, "Short Qualifying"),
            Map.entry(9, "One-Shot Qualifying"),
            Map.entry(10, "Race"),
            Map.entry(11, "Race 2"),
            Map.entry(12, "Race 3"),
            Map.entry(13, "Time Trial")
    );

    private GameMappings() {}

    public static String trackName(int id) {
        return TRACKS.getOrDefault(id, "Track " + id);
    }

    public static String sessionTypeName(int id) {
        return SESSION_TYPES.getOrDefault(id, "Type " + id);
    }
}
