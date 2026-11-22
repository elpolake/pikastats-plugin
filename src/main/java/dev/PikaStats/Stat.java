package dev.PikaStats;

/** Raw counters returned by the leaderboard endpoint, keyed by their API names. */
enum Stat {
    WINS("Wins", "Wins", "W"),
    LOSSES("Losses", "Losses", "L"),
    FINAL_KILLS("Final kills", "Finals", "FK"),
    FINAL_DEATHS("Final deaths", "Final Deaths", "FD"),
    KILLS("Kills", "Kills", "K"),
    DEATHS("Deaths", "Deaths", "D"),
    GAMES("Games played", "Games", "GP"),
    BEDS("Beds destroyed", "Beds", "BB"),
    MELEE_KILLS("Melee kills", "Melee Kills", "MK"),
    BOW_KILLS("Bow kills", "Bow Kills", "BK"),
    VOID_KILLS("Void kills", "Void Kills", "VK"),
    ARROWS_SHOT("Arrows shot", "Arrows Shot", "AS"),
    ARROWS_HIT("Arrows hit", "Arrows Hit", "AH"),
    WINSTREAK("Highest winstreak reached", "Best WS", "WS");

    private final String apiName;
    private final String label;
    private final String shortLabel;

    Stat(String apiName, String label, String shortLabel) {
        this.apiName = apiName;
        this.label = label;
        this.shortLabel = shortLabel;
    }

    String apiName() {
        return this.apiName;
    }

    String label() {
        return this.label;
    }

    String shortLabel() {
        return this.shortLabel;
    }
}
