package dev.PikaStats;

import java.util.Locale;

/** Game modes accepted by the leaderboard API. SkyWars only uses ALL_MODES, SOLO and DOUBLES. */
enum BedWarsMode {
    ALL_MODES("a", "ALL_MODES", "Overall", "All"),
    SOLO("1", "SOLO", "Solo", "1s"),
    DOUBLES("2", "DOUBLES", "Doubles", "2s"),
    TRIPLES("3", "TRIPLES", "Triples", "3s"),
    // The live player-leaderboard endpoint currently accepts QUAD (singular).
    QUADS("4", "QUAD", "Quads", "4s");

    private final String commandKey;
    private final String apiValue;
    private final String displayName;
    private final String shortName;

    BedWarsMode(String commandKey, String apiValue, String displayName, String shortName) {
        this.commandKey = commandKey;
        this.apiValue = apiValue;
        this.displayName = displayName;
        this.shortName = shortName;
    }

    String apiValue() {
        return this.apiValue;
    }

    String displayName() {
        return this.displayName;
    }

    String shortName() {
        return this.shortName;
    }

    /** Parses a chat token such as {@code 4}, {@code quads}, {@code duos} or {@code 2s}. */
    static BedWarsMode parse(String value) {
        if (value == null) {
            return null;
        }
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "a", "all", "all_modes", "allmodes", "overall", "any" -> ALL_MODES;
            case "1", "1s", "s", "solo", "solos", "singles" -> SOLO;
            case "2", "2s", "d", "duo", "duos", "double", "doubles" -> DOUBLES;
            case "3", "3s", "trio", "trios", "triple", "triples" -> TRIPLES;
            case "4", "4s", "quad", "quads", "four", "fours", "4v4", "4v4v4v4", "quadruples" -> QUADS;
            default -> null;
        };
    }

    /** Parses names sent by the overlay or stored in the config. */
    static BedWarsMode fromName(String value) {
        if (value == null) {
            return null;
        }
        for (BedWarsMode mode : values()) {
            if (mode.name().equalsIgnoreCase(value) || mode.displayName.equalsIgnoreCase(value)
                    || mode.apiValue.equalsIgnoreCase(value) || mode.commandKey.equalsIgnoreCase(value)) {
                return mode;
            }
        }
        return parse(value);
    }
}
