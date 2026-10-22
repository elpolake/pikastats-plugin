package dev.PikaStats;

import java.util.Locale;

enum StatsField {
    SUMMARY("Summary"),
    WINS("Wins"),
    LOSSES("Losses"),
    FINAL_KILLS("Final Kills"),
    FINAL_DEATHS("Final Deaths"),
    KILLS("Kills"),
    DEATHS("Deaths"),
    FKDR("FKDR"),
    WLR("WLR"),
    KDR("KDR");

    private final String displayName;

    StatsField(String displayName) {
        this.displayName = displayName;
    }

    String displayName() {
        return this.displayName;
    }

    static StatsField parse(String value) {
        if (value == null) {
            return SUMMARY;
        }

        String normalized = value.toLowerCase(Locale.ROOT).replace('-', '_');
        return switch (normalized) {
            case "win", "wins" -> WINS;
            case "loss", "losses" -> LOSSES;
            case "final", "finals", "final_kill", "final_kills", "finalkill", "finalkills" -> FINAL_KILLS;
            case "final_death", "final_deaths", "finaldeath", "finaldeaths" -> FINAL_DEATHS;
            case "kill", "kills" -> KILLS;
            case "death", "deaths" -> DEATHS;
            case "fkdr" -> FKDR;
            case "wlr" -> WLR;
            case "kdr" -> KDR;
            default -> null;
        };
    }
}
