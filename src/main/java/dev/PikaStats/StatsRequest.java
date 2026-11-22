package dev.PikaStats;

import java.util.List;
import java.util.Locale;

/**
 * A parsed stats lookup. Arguments after the player name may appear in any order, e.g.
 * {@code -stats Steve w 4}, {@code -stats Steve quads weekly fkdr}, {@code -stats Steve full q}.
 */
record StatsRequest(String username, GameType game, StatsPeriod period, BedWarsMode mode, StatsView view,
                    boolean quiet) {

    StatsRequest(String username, StatsPeriod period, BedWarsMode mode) {
        this(username, GameType.BEDWARS, period, mode, StatsView.SUMMARY, false);
    }

    /** Words that make a command reply only to you instead of public chat. */
    static boolean isQuietToken(String token) {
        return switch (token.toLowerCase(Locale.ROOT)) {
            case "q", "quiet", "priv", "private", "me", "local", "silent" -> true;
            default -> false;
        };
    }

    /**
     * Parses arguments that follow the command word. Returns null when anything is unrecognised so
     * a typo never turns into a surprising public lookup.
     */
    static StatsRequest parseArguments(GameType game, List<String> arguments) {
        if (arguments.isEmpty() || !PartyStore.isValidUsername(arguments.get(0))) {
            return null;
        }
        String username = arguments.get(0);
        StatsPeriod period = null;
        BedWarsMode mode = null;
        StatsView view = null;
        boolean quiet = false;
        for (String token : arguments.subList(1, arguments.size())) {
            if (isQuietToken(token)) {
                quiet = true;
                continue;
            }
            // "a" is ambiguous: the legacy syntax was "<period> <mode>", so the first "a" is the
            // period and a second one is the mode ("-stats Steve a a").
            if (token.equalsIgnoreCase("a")) {
                if (period == null) {
                    period = StatsPeriod.ALL_TIME;
                } else if (mode == null) {
                    mode = BedWarsMode.ALL_MODES;
                } else {
                    return null;
                }
                continue;
            }
            StatsPeriod parsedPeriod = StatsPeriod.parse(token);
            if (parsedPeriod != null && period == null) {
                period = parsedPeriod;
                continue;
            }
            BedWarsMode parsedMode = BedWarsMode.parse(token);
            if (parsedMode != null && mode == null) {
                mode = parsedMode;
                continue;
            }
            StatsView parsedView = StatsView.parse(token);
            if (parsedView != null && view == null) {
                view = parsedView;
                continue;
            }
            return null;
        }
        mode = mode == null ? BedWarsMode.ALL_MODES : mode;
        if (game == GameType.SKYWARS && mode != BedWarsMode.ALL_MODES && mode != BedWarsMode.SOLO
                && mode != BedWarsMode.DOUBLES) {
            return null;
        }
        return new StatsRequest(username, game, period == null ? StatsPeriod.ALL_TIME : period, mode,
                view == null ? StatsView.SUMMARY : view, quiet);
    }

    StatsRequest withUsername(String name) {
        return new StatsRequest(name, this.game, this.period, this.mode, this.view, this.quiet);
    }

    /** "BW Overall" or "BW Weekly Quads" – compact scope label for chat lines. */
    String scopeLabel() {
        StringBuilder label = new StringBuilder(this.game.shortName());
        if (this.period != StatsPeriod.ALL_TIME) {
            label.append(' ').append(this.period.displayName());
        }
        label.append(' ').append(this.mode == BedWarsMode.ALL_MODES && this.period != StatsPeriod.ALL_TIME
                ? "All" : this.mode.displayName());
        return label.toString();
    }

    /** The scope as shown in replies: nothing for the default (BedWars, lifetime, all modes). */
    String shownScope() {
        boolean standard = this.game == GameType.BEDWARS && this.period == StatsPeriod.ALL_TIME
                && this.mode == BedWarsMode.ALL_MODES;
        return standard ? "" : scopeLabel();
    }

    String cacheKey() {
        return this.username.toLowerCase(Locale.ROOT) + ':' + this.game + ':' + this.period + ':' + this.mode;
    }
}
