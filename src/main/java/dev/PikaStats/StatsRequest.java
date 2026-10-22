package dev.PikaStats;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

record StatsRequest(String username, StatsPeriod period, BedWarsMode mode, StatsField field) {
    StatsRequest(String username, StatsPeriod period, BedWarsMode mode) {
        this(username, period, mode, StatsField.SUMMARY);
    }

    private static final Pattern COMMAND = Pattern.compile(
            "^-stats\\s+([A-Za-z0-9_]{1,16})(?:\\s+([aAwWmMyY])\\s+([aA1-4]|ALL_MODES))?(?:\\s+([A-Za-z][A-Za-z0-9_-]*))?$",
            Pattern.CASE_INSENSITIVE);

    static StatsRequest parse(String text) {
        if (text == null) {
            return null;
        }
        String command = text.strip();
        Matcher matcher = COMMAND.matcher(command);
        if (!matcher.matches()) {
            return null;
        }

        StatsPeriod period = matcher.group(2) == null
                ? StatsPeriod.ALL_TIME
                : StatsPeriod.parse(matcher.group(2));
        BedWarsMode mode = matcher.group(3) == null
                ? BedWarsMode.ALL_MODES
                : BedWarsMode.parse(matcher.group(3));
        StatsField field = StatsField.parse(matcher.group(4));
        if (period == null || mode == null || field == null) {
            return null;
        }
        return new StatsRequest(matcher.group(1), period, mode, field);
    }
}
