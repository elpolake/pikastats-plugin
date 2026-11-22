package dev.PikaStats;

import java.util.Locale;

/** What a stats command should print: a summary, a multi-line breakdown, or one stat. */
enum StatsView {
    SUMMARY,
    FULL,
    PLACES,
    COMBAT,
    WINS,
    LOSSES,
    FINAL_KILLS,
    FINAL_DEATHS,
    KILLS,
    DEATHS,
    GAMES,
    BEDS,
    WINSTREAK,
    FKDR,
    WLR,
    KDR,
    BBLR,
    WINRATE,
    FPG,
    ACCURACY;

    /** Returns the view for a chat token, or null when the token is not a view selector. */
    static StatsView parse(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.toLowerCase(Locale.ROOT).replace('-', '_');
        return switch (normalized) {
            case "summary", "sum", "short" -> SUMMARY;
            case "full", "all_stats", "allstats", "detail", "details", "more", "everything" -> FULL;
            case "places", "place", "lb", "leaderboard", "ranks", "rank", "pos", "position" -> PLACES;
            case "combat", "pvp", "fight" -> COMBAT;
            case "win", "wins" -> WINS;
            case "loss", "losses" -> LOSSES;
            case "final", "finals", "final_kill", "final_kills", "finalkill", "finalkills", "fk", "fks" -> FINAL_KILLS;
            case "final_death", "final_deaths", "finaldeath", "finaldeaths", "fd", "fds" -> FINAL_DEATHS;
            case "kill", "kills", "k" -> KILLS;
            case "death", "deaths" -> DEATHS;
            case "games", "game", "played", "gp", "games_played" -> GAMES;
            case "bed", "beds", "bb", "beds_broken", "bedsbroken", "beds_destroyed" -> BEDS;
            case "ws", "winstreak", "streak", "best_ws", "bestws" -> WINSTREAK;
            case "fkdr" -> FKDR;
            case "wlr", "wl" -> WLR;
            case "kdr", "kd" -> KDR;
            case "bblr" -> BBLR;
            case "winrate", "wr", "win_rate", "win%" -> WINRATE;
            case "fpg", "finals_per_game" -> FPG;
            case "acc", "accuracy", "bow", "aim" -> ACCURACY;
            default -> null;
        };
    }
}
