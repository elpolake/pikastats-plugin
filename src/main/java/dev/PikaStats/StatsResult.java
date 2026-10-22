package dev.PikaStats;

record StatsResult(
        String canonicalUsername,
        long wins,
        long losses,
        long finalKills,
        long finalDeaths,
        long kills,
        long deaths,
        long gamesPlayed,
        long bedsDestroyed,
        long meleeKills,
        long bowKills,
        long voidKills,
        long arrowsShot,
        long arrowsHit,
        long highestWinStreakReached) {

    String fkdr() {
        return ratio(this.finalKills, this.finalDeaths);
    }

    String wlr() {
        return ratio(this.wins, this.losses);
    }

    String kdr() {
        return ratio(this.kills, this.deaths);
    }

    String publicMessage(StatsRequest request) {
        String prefix = this.canonicalUsername + " " + request.period().displayName() + " "
                + request.mode().displayName() + ": ";
        return switch (request.field()) {
            case SUMMARY -> prefix + "FKDR " + fkdr() + " WLR " + wlr() + " KDR " + kdr();
            case WINS -> countMessage(prefix, request.field(), this.wins);
            case LOSSES -> countMessage(prefix, request.field(), this.losses);
            case FINAL_KILLS -> countMessage(prefix, request.field(), this.finalKills);
            case FINAL_DEATHS -> countMessage(prefix, request.field(), this.finalDeaths);
            case KILLS -> countMessage(prefix, request.field(), this.kills);
            case DEATHS -> countMessage(prefix, request.field(), this.deaths);
            case FKDR -> prefix + "FKDR " + fkdr();
            case WLR -> prefix + "WLR " + wlr();
            case KDR -> prefix + "KDR " + kdr();
        };
    }

    private static String countMessage(String prefix, StatsField field, long value) {
        return prefix + field.displayName() + " " + String.format(java.util.Locale.ROOT, "%,d", value);
    }

    private static String ratio(long numerator, long denominator) {
        if (denominator <= 0) {
            return "0.00";
        }
        return String.format(java.util.Locale.ROOT, "%.2f", (double) numerator / denominator);
    }
}
