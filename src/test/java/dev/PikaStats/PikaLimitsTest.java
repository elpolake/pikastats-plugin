package dev.PikaStats;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real limits measured on PikaNetwork: a 1.8 connection carries at most 100
 * characters, party replies lose 5 to "/p c ", only ASCII passes the chat filter, and public/global
 * chat shows color codes as text. Prints every reply so the layout can be judged.
 */
class PikaLimitsTest {
    private static final int PARTY_BUDGET = 100 - "/p c ".length();
    private static final int GLOBAL_BUDGET = 100 - "!".length();

    @Test
    void longLinesAreSplitWithoutLosingText() {
        StatsResult stats = ChatRenderingTest.sample("Isabe5lGod4117");
        ChatCharset ascii = new ChatCharset();
        StatsFormatter formatter = new StatsFormatter(ChatTheme.get("midnight"));
        StatsRequest places = new StatsRequest("Isabe5lGod4117", GameType.BEDWARS, StatsPeriod.WEEKLY,
                BedWarsMode.QUADS, StatsView.PLACES, false);
        ChatLine longLine = formatter.stats(places, stats).get(0);
        PunishmentsResult punishments = new PunishmentsResult("Isabe5lGod4117", 5, 3, 1, 1, List.of(
                new PunishmentsResult.Entry("K", "26-10-13", "You are sending invalid packets to the server. If this keeps"
                        + " happening please contact a server administrator at discord.gg/pika", "", false)));
        List<ChatLine> lines = new java.util.ArrayList<>(List.of(longLine));
        lines.addAll(formatter.punishments("Isabe5lGod4117", punishments, true, false));
        for (ChatLine line : lines) {
            for (ChatFormat.ColorMode mode : List.of(ChatFormat.ColorMode.HEX, ChatFormat.ColorMode.LEGACY, ChatFormat.ColorMode.NONE)) {
                List<String> parts = line.renderSplit(ascii, mode, PARTY_BUDGET);
                StringBuilder visible = new StringBuilder();
                for (String part : parts) {
                    System.out.printf("%-6s %3d %s%n", mode, part.length(), part);
                    assertTrue(part.length() <= PARTY_BUDGET, part);
                    visible.append(ChatFormat.stripCodes(part)).append(' ');
                }
                String all = line.plain(ascii);
                for (String word : all.replace("|", " ").split("\\s+")) {
                    assertTrue(visible.toString().contains(word), "lost '" + word + "' in " + parts);
                }
            }
        }
    }

    @Test
    void repliesFitPikaLimitsAndStayAscii() {
        StatsResult stats = ChatRenderingTest.sample("Isabe5lGod4117");
        ChatCharset ascii = new ChatCharset();
        StatsFormatter formatter = new StatsFormatter(ChatTheme.get("midnight"));
        for (StatsView view : List.of(StatsView.SUMMARY, StatsView.FULL, StatsView.PLACES, StatsView.COMBAT, StatsView.FKDR, StatsView.WINS)) {
            StatsRequest request = new StatsRequest("Isabe5lGod4117", GameType.BEDWARS, StatsPeriod.ALL_TIME,
                    BedWarsMode.ALL_MODES, view, false);
            for (ChatLine line : formatter.stats(request, stats)) {
                // Same choices Reply makes: hex in party chat (Pika strips &c codes), colors kept first.
                String party = line.renderColorsFirst(ascii, ChatFormat.ColorMode.HEX, PARTY_BUDGET);
                String global = line.renderColorsFirst(ascii, ChatFormat.ColorMode.NONE, GLOBAL_BUDGET);
                assertTrue(party.contains("&#"), "party reply lost its colors: " + party);
                System.out.printf("%-8s party  %3d %s%n         visible  %s%n         global %3d %s%n", view,
                        party.length(), party, ChatFormat.stripCodes(party), global.length(), global);
                assertTrue(party.length() <= PARTY_BUDGET && global.length() <= GLOBAL_BUDGET);
                assertTrue((party + global).chars().allMatch(c -> c >= 0x20 && c < 0x7F), "non-ASCII would be filtered");
                assertTrue(!global.contains("&"), "codes would show as text in global chat");
            }
        }
    }
}
