package dev.PikaStats;

import java.util.Locale;

enum StatsPeriod {
    ALL_TIME("a", "total", "All Time", "Lifetime"),
    WEEKLY("w", "weekly", "Weekly", "Weekly"),
    MONTHLY("m", "monthly", "Monthly", "Monthly"),
    YEARLY("y", "yearly", "Yearly", "Yearly");

    private final String commandKey;
    private final String apiValue;
    private final String displayName;
    private final String shortLabel;

    StatsPeriod(String commandKey, String apiValue, String displayName, String shortLabel) {
        this.commandKey = commandKey;
        this.apiValue = apiValue;
        this.displayName = displayName;
        this.shortLabel = shortLabel;
    }

    String apiValue() {
        return this.apiValue;
    }

    String displayName() {
        return this.displayName;
    }

    String shortLabel() {
        return this.shortLabel;
    }

    /** Parses a chat token such as {@code w}, {@code weekly} or {@code lifetime}. */
    static StatsPeriod parse(String value) {
        if (value == null) {
            return null;
        }
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "a", "at", "all", "alltime", "all_time", "all-time", "total", "lifetime", "life", "lt" -> ALL_TIME;
            case "w", "wk", "week", "weekly" -> WEEKLY;
            case "m", "mo", "month", "monthly" -> MONTHLY;
            case "y", "yr", "year", "yearly", "annual" -> YEARLY;
            default -> null;
        };
    }

    static StatsPeriod fromName(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.strip();
        for (StatsPeriod period : values()) {
            if (period.name().equalsIgnoreCase(normalized)
                    || period.commandKey.equalsIgnoreCase(normalized)
                    || period.apiValue.equalsIgnoreCase(normalized)
                    || period.displayName.equalsIgnoreCase(normalized)) {
                return period;
            }
        }
        return parse(normalized);
    }
}
