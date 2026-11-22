package dev.PikaStats;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static dev.PikaStats.ChatTheme.Tier;
import static dev.PikaStats.StatsResult.formatCompact;
import static dev.PikaStats.StatsResult.formatCount;
import static dev.PikaStats.StatsResult.formatPercent;
import static dev.PikaStats.StatsResult.formatRatio;

/** Turns API results into themed chat lines. Every method returns lines ready for {@link Reply}. */
final class StatsFormatter {
    private final ChatTheme theme;

    StatsFormatter(ChatTheme theme) {
        this.theme = theme;
    }

    private ChatLine line() {
        return new ChatLine(this.theme);
    }

    /** Icon + name in the name color, then the scope (only when not the default) in muted. */
    private ChatLine header(String name, String scope) {
        ChatLine line = line().head(name);
        if (scope != null && !scope.isEmpty()) {
            line.muted(" " + scope);
        }
        return line;
    }

    private String color(int index) {
        return this.theme.statColor(index);
    }

    // ---------------------------------------------------------------- stats

    List<ChatLine> stats(StatsRequest request, StatsResult stats) {
        if (stats.isEmpty()) {
            return List.of(header(stats.username(), request.shownScope()).piece("", "No games played", this.theme.warn()));
        }
        return switch (request.view()) {
            case SUMMARY -> List.of(summary(request, stats));
            case FULL -> full(request, stats);
            case PLACES -> List.of(places(request, stats));
            case COMBAT -> List.of(combat(request, stats));
            default -> List.of(single(request, stats));
        };
    }

    /** Every stat in its own color; the lowest-priority stats are trimmed when the line is too long. */
    private ChatLine summary(StatsRequest request, StatsResult stats) {
        ChatLine line = header(stats.username(), request.shownScope());
        if (request.game() == GameType.BEDWARS) {
            line.piece(0, "FKDR", formatRatio(stats.fkdr()), color(0));
            line.piece(0, "WLR", formatRatio(stats.wlr()), color(1));
            line.piece(1, "KDR", formatRatio(stats.kdr()), color(2));
            line.piece(2, "W", formatCompact(stats.get(Stat.WINS)), color(3));
            line.piece(3, "FK", formatCompact(stats.get(Stat.FINAL_KILLS)), color(4));
            line.piece(4, "WS", formatCount(stats.get(Stat.WINSTREAK)), color(5));
        } else {
            line.piece(0, "WLR", formatRatio(stats.wlr()), color(0));
            line.piece(0, "KDR", formatRatio(stats.kdr()), color(1));
            line.piece(1, "W", formatCompact(stats.get(Stat.WINS)), color(2));
            line.piece(2, "K", formatCompact(stats.get(Stat.KILLS)), color(3));
            line.piece(3, "WS", formatCount(stats.get(Stat.WINSTREAK)), color(4));
        }
        return line;
    }

    private List<ChatLine> full(StatsRequest request, StatsResult stats) {
        List<ChatLine> lines = new ArrayList<>();
        ChatLine first = header(stats.username(), request.shownScope());
        int index = 0;
        if (request.game() == GameType.BEDWARS) {
            first.piece(0, "FKDR", formatRatio(stats.fkdr()), color(index++));
        }
        first.piece(0, "WLR", formatRatio(stats.wlr()), color(index++));
        first.piece(1, "KDR", formatRatio(stats.kdr()), color(index++));
        if (request.game() == GameType.BEDWARS) {
            first.piece(2, "BBLR", formatRatio(stats.bblr()), color(index));
        }
        lines.add(first);

        ChatLine second = line().iconOnly();
        second.add2(" Wins " + formatCompact(stats.get(Stat.WINS)), color(0));
        second.piece(0, "Losses", formatCompact(stats.get(Stat.LOSSES)), color(1));
        second.piece(1, "WR", formatPercent(stats.winRate()), color(2));
        second.piece(2, "Games", formatCompact(stats.get(Stat.GAMES)), color(3));
        second.piece(3, "Best WS", formatCount(stats.get(Stat.WINSTREAK)), color(4));
        lines.add(second);

        ChatLine third = line().iconOnly();
        if (request.game() == GameType.BEDWARS) {
            third.add2(" Finals " + formatCompact(stats.get(Stat.FINAL_KILLS)), color(0));
            third.piece(0, "FDeaths", formatCompact(stats.get(Stat.FINAL_DEATHS)), color(1));
            third.piece(1, "Beds", formatCompact(stats.get(Stat.BEDS)), color(2));
            third.piece(2, "FPG", formatRatio(stats.finalsPerGame()), color(3));
            third.piece(3, "Kills", formatCompact(stats.get(Stat.KILLS)), color(4));
        } else {
            third.add2(" Kills " + formatCompact(stats.get(Stat.KILLS)), color(0));
            third.piece(0, "Deaths", formatCompact(stats.get(Stat.DEATHS)), color(1));
            third.piece(1, "Void", formatCompact(stats.get(Stat.VOID_KILLS)), color(2));
            third.piece(2, "Acc", formatPercent(stats.bowAccuracy()), color(3));
        }
        lines.add(third);
        return lines;
    }

    private ChatLine places(StatsRequest request, StatsResult stats) {
        ChatLine line = header(stats.username(), request.shownScope().isEmpty() ? "LB" : request.shownScope() + " LB");
        Stat[] order = request.game() == GameType.BEDWARS
                ? new Stat[]{Stat.FINAL_KILLS, Stat.WINS, Stat.BEDS, Stat.KILLS, Stat.WINSTREAK, Stat.GAMES}
                : new Stat[]{Stat.WINS, Stat.KILLS, Stat.WINSTREAK, Stat.GAMES};
        int index = 0;
        for (Stat stat : order) {
            long place = stats.place(stat);
            if (place > 0) {
                line.piece(index < 2 ? 0 : index, stat.shortLabel(), "#" + formatCount(place), color(index));
                index++;
            }
        }
        if (index == 0) {
            line.piece("", "Unranked", this.theme.warn());
        }
        return line;
    }

    private ChatLine combat(StatsRequest request, StatsResult stats) {
        ChatLine line = header(stats.username(), request.shownScope());
        line.piece(0, "Melee", formatCompact(stats.get(Stat.MELEE_KILLS)), color(0));
        line.piece(0, "Void", formatCompact(stats.get(Stat.VOID_KILLS)), color(1));
        line.piece(1, "Bow", formatCompact(stats.get(Stat.BOW_KILLS)), color(2));
        line.piece(2, "Acc", formatPercent(stats.bowAccuracy()), color(3));
        line.piece(3, "KDR", formatRatio(stats.kdr()), color(4));
        return line;
    }

    private ChatLine single(StatsRequest request, StatsResult stats) {
        ChatLine line = header(stats.username(), request.shownScope());
        switch (request.view()) {
            case FKDR -> ratioLine(line, "FKDR", Tier.FKDR, stats.fkdr(), stats.get(Stat.FINAL_KILLS), stats.get(Stat.FINAL_DEATHS));
            case WLR -> ratioLine(line, "WLR", Tier.WLR, stats.wlr(), stats.get(Stat.WINS), stats.get(Stat.LOSSES));
            case KDR -> ratioLine(line, "KDR", Tier.KDR, stats.kdr(), stats.get(Stat.KILLS), stats.get(Stat.DEATHS));
            case BBLR -> ratioLine(line, "BBLR", Tier.BBLR, stats.bblr(), stats.get(Stat.BEDS), stats.get(Stat.LOSSES));
            case WINRATE -> {
                line.piece(0, "Win rate", formatPercent(stats.winRate()), color(0));
                line.piece(1, "", formatCount(stats.get(Stat.WINS)) + "/" + formatCount(stats.get(Stat.GAMES)) + " games", color(1));
            }
            case FPG -> line.piece(0, "Finals/game", formatRatio(stats.finalsPerGame()), color(0));
            case ACCURACY -> {
                line.piece(0, "Bow acc", formatPercent(stats.bowAccuracy()), color(0));
                line.piece(1, "", formatCount(stats.get(Stat.ARROWS_HIT)) + "/" + formatCount(stats.get(Stat.ARROWS_SHOT)) + " arrows", color(1));
            }
            default -> {
                Stat stat = switch (request.view()) {
                    case WINS -> Stat.WINS;
                    case LOSSES -> Stat.LOSSES;
                    case FINAL_KILLS -> Stat.FINAL_KILLS;
                    case FINAL_DEATHS -> Stat.FINAL_DEATHS;
                    case KILLS -> Stat.KILLS;
                    case DEATHS -> Stat.DEATHS;
                    case GAMES -> Stat.GAMES;
                    case BEDS -> Stat.BEDS;
                    default -> Stat.WINSTREAK;
                };
                line.piece(0, stat.label(), formatCount(stats.get(stat)), color(0));
                long place = stats.place(stat);
                if (place > 0) {
                    line.piece(1, "LB", "#" + formatCount(place), color(1));
                }
            }
        }
        return line;
    }

    private void ratioLine(ChatLine line, String label, Tier tier, double ratio, long top, long bottom) {
        line.piece(0, label, formatRatio(ratio), color(0));
        line.piece(1, "", formatCount(top) + "/" + formatCount(bottom), color(1));
        line.piece(2, "", tier.label(ratio), color(2));
    }

    // ---------------------------------------------------------------- compare

    List<ChatLine> compare(StatsRequest request, StatsResult first, StatsResult second) {
        // Two names in two colors; each stat shows "first/second" with the winner's side green.
        ChatLine line = line().head(first.username()).add2(" vs ", this.theme.muted()).add2(second.username(), color(1));
        int priority = 0;
        if (request.game() == GameType.BEDWARS) {
            compareStat(line, "FKDR", formatRatio(first.fkdr()), formatRatio(second.fkdr()), first.fkdr() >= second.fkdr(), priority);
        }
        compareStat(line, "WLR", formatRatio(first.wlr()), formatRatio(second.wlr()), first.wlr() >= second.wlr(), priority);
        compareStat(line, "KDR", formatRatio(first.kdr()), formatRatio(second.kdr()), first.kdr() >= second.kdr(), 1);
        compareStat(line, "W", formatCompact(first.get(Stat.WINS)), formatCompact(second.get(Stat.WINS)),
                first.get(Stat.WINS) >= second.get(Stat.WINS), 2);
        return List.of(line);
    }

    private void compareStat(ChatLine line, String label, String first, String second, boolean firstWins, int priority) {
        String winner = this.theme.good();
        String loser = this.theme.bad();
        java.util.function.Consumer<ChatLine> body = l -> l.piece(label, first, firstWins ? winner : loser)
                .add2("/" + second, firstWins ? loser : winner);
        if (priority <= 0) {
            body.accept(line);
        } else {
            line.optional(priority, body);
        }
    }

    // ---------------------------------------------------------------- profile

    List<ChatLine> profile(ProfileResult profile) {
        ChatLine line = line().head(profile.username());
        String level = profile.level() + (profile.levelTitle().isBlank() ? "" : " " + profile.levelTitle());
        line.piece(0, "Lv", level, color(0));
        if (!profile.primaryRank().isBlank()) {
            line.piece(0, "", profile.primaryRank(), color(1));
        }
        if (!profile.clanName().isBlank()) {
            line.piece(1, "Clan", profile.clanName() + (profile.clanLevel() > 0 ? " Lv" + profile.clanLevel() : ""), color(2));
        }
        if (profile.lastSeenMillis() > 0) {
            line.piece(2, "Seen", relativeTime(profile.lastSeenMillis()), color(3));
        }
        line.piece(3, "Friends", Integer.toString(profile.friends()), color(4));
        line.piece(4, "", String.format(Locale.ROOT, "%.0f%% to next", profile.levelProgress()), color(5));
        return List.of(line);
    }

    static String relativeTime(long epochMillis) {
        long seconds = Math.max(0, (System.currentTimeMillis() - epochMillis) / 1000);
        if (seconds < 120) {
            return "now";
        }
        long minutes = seconds / 60;
        if (minutes < 60) {
            return minutes + "m ago";
        }
        long hours = minutes / 60;
        if (hours < 48) {
            return hours + "h ago";
        }
        long days = hours / 24;
        if (days < 60) {
            return days + "d ago";
        }
        return (days / 30) + "mo ago";
    }

    // ---------------------------------------------------------------- punishments

    List<ChatLine> punishments(String username, PunishmentsResult result, boolean withReasons, boolean countOnly) {
        List<ChatLine> lines = new ArrayList<>();
        ChatLine counts = line().icon().name(PartyStore.isValidUsername(result.username()) ? result.username() : username);
        countPart(counts, "Bans", result.bans(), 0);
        countPart(counts, "Mutes", result.mutes(), 0);
        countPart(counts, "Warns", result.warnings(), 1);
        countPart(counts, "Kicks", result.kicks(), 2);
        List<PunishmentsResult.Entry> active = result.activePunishments();
        if (!countOnly) {
            if (!active.isEmpty()) {
                PunishmentsResult.Entry first = active.get(0);
                counts.sep().bad("ACTIVE " + typeName(first.type()).toUpperCase(Locale.ROOT));
            } else if (!withReasons && !result.entries().isEmpty()) {
                int priority = 3;
                for (PunishmentsResult.Entry entry : result.entries().subList(0, Math.min(3, result.entries().size()))) {
                    counts.optional(priority++, l -> l.sep().muted(typeName(entry.type())
                            + (entry.date().isBlank() ? "" : " " + entry.date())));
                }
            }
        }
        lines.add(counts);
        if (withReasons) {
            if (result.entries().isEmpty()) {
                lines.add(line().icon().good("Clean record"));
            }
            for (PunishmentsResult.Entry entry : result.entries()) {
                ChatLine reason = line().icon();
                String type = typeName(entry.type());
                if (entry.active()) {
                    reason.bad(type + " ACTIVE");
                } else {
                    reason.warn(type);
                }
                if (!entry.date().isBlank()) {
                    reason.muted(" " + entry.date());
                }
                reason.sep().text(PunishmentsResult.safeReason(entry.reason()));
                lines.add(reason.splittable());
            }
        }
        return lines;
    }

    private void countPart(ChatLine line, String label, int count, int priority) {
        String color = count == 0 ? this.theme.good() : count < 3 ? this.theme.warn() : this.theme.bad();
        if (priority == 0) {
            line.sep().label(label + " ").value(Integer.toString(count), color);
        } else {
            line.optional(priority, l -> l.sep().label(label + " ").value(Integer.toString(count), color));
        }
    }

    static String typeName(String type) {
        return switch (type) {
            case "B" -> "Ban";
            case "K" -> "Kick";
            case "M" -> "Mute";
            case "W" -> "Warn";
            default -> "Punishment";
        };
    }

    // ---------------------------------------------------------------- lobby

    record Threat(String name, double fkdr, double wlr, double score, boolean party) {
    }

    List<ChatLine> threats(List<Threat> threats, String scope) {
        ChatLine line = line().icon().accent("Threats").muted(" " + scope);
        if (threats.isEmpty()) {
            return List.of(line.sep().muted("no stats loaded yet"));
        }
        int priority = 0;
        for (Threat threat : threats) {
            if (priority == 0) {
                threatPart(line, threat);
            } else {
                line.optional(priority, l -> threatPart(l, threat));
            }
            priority++;
        }
        return List.of(line);
    }

    private void threatPart(ChatLine line, Threat threat) {
        line.sep().name(threat.name()).text(" ").tier(Tier.FKDR, threat.fkdr(), formatRatio(threat.fkdr()));
    }

    // ---------------------------------------------------------------- misc

    ChatLine error(String message) {
        return line().icon().bad(message);
    }

    ChatLine info(String message) {
        return line().icon().text(message);
    }

    ChatLine notice(String title, String message) {
        return line().icon().accent(title).sep().text(message);
    }
}
