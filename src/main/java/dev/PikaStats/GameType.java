package dev.PikaStats;

/** Minigames exposed by the PikaNetwork leaderboard API. */
enum GameType {
    BEDWARS("bedwars", "BW", "BedWars"),
    SKYWARS("skywars", "SW", "SkyWars");

    private final String apiValue;
    private final String shortName;
    private final String displayName;

    GameType(String apiValue, String shortName, String displayName) {
        this.apiValue = apiValue;
        this.shortName = shortName;
        this.displayName = displayName;
    }

    String apiValue() {
        return this.apiValue;
    }

    String shortName() {
        return this.shortName;
    }

    String displayName() {
        return this.displayName;
    }
}
