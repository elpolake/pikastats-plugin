package dev.PikaStats;

enum BedWarsMode {
    ALL_MODES("a", "ALL_MODES", "All Modes"),
    SOLO("1", "SOLO", "Solo"),
    DOUBLES("2", "DOUBLES", "Doubles"),
    TRIPLES("3", "TRIPLES", "Triples"),
    // The live player-leaderboard endpoint currently accepts QUAD (singular).
    QUADS("4", "QUAD", "Quads");

    private final String commandKey;
    private final String apiValue;
    private final String displayName;

    BedWarsMode(String commandKey, String apiValue, String displayName) {
        this.commandKey = commandKey;
        this.apiValue = apiValue;
        this.displayName = displayName;
    }

    String apiValue() {
        return this.apiValue;
    }

    String displayName() {
        return this.displayName;
    }

    static BedWarsMode parse(String value) {
        if (value == null) {
            return null;
        }
        if (value.equalsIgnoreCase("ALL_MODES")) {
            return ALL_MODES;
        }
        for (BedWarsMode mode : values()) {
            if (mode.commandKey.equalsIgnoreCase(value)) {
                return mode;
            }
        }
        return null;
    }

    static BedWarsMode fromName(String value) {
        if (value == null) {
            return null;
        }
        for (BedWarsMode mode : values()) {
            if (mode.name().equalsIgnoreCase(value) || mode.displayName.equalsIgnoreCase(value)
                    || mode.apiValue.equalsIgnoreCase(value)) {
                return mode;
            }
        }
        return parse(value);
    }
}
