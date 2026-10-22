package dev.PikaStats;

import java.util.Locale;

enum StatsPeriod {
    ALL_TIME("a", "total", "All Time"),
    WEEKLY("w", "weekly", "Weekly"),
    MONTHLY("m", "monthly", "Monthly"),
    YEARLY("y", "yearly", "Yearly");

    private final String commandKey;
    private final String apiValue;
    private final String displayName;

    StatsPeriod(String commandKey, String apiValue, String displayName) {
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

    static StatsPeriod parse(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.toLowerCase(Locale.ROOT);
        for (StatsPeriod period : values()) {
            if (period.commandKey.equals(normalized)) {
                return period;
            }
        }
        return null;
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
        return null;
    }
}
