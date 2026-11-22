package dev.PikaStats;

import com.viaversion.viaversion.libs.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatRenderingTest {
    private static final Pattern BROKEN_CODE = Pattern.compile("&#[0-9a-fA-F]{0,5}$");

    /** Long name and big numbers: the worst case for the 128-character limit. */
    static StatsResult sample(String name) {
        String json = """
                {"Wins":{"entries":[{"place":1336,"value":"2172","id":"%1$s"}]},
                 "Losses":{"entries":[{"place":13586,"value":"1485","id":"%1$s"}]},
                 "Final kills":{"entries":[{"place":1570,"value":"7509","id":"%1$s"}]},
                 "Final deaths":{"entries":[{"place":10043,"value":"1643","id":"%1$s"}]},
                 "Kills":{"entries":[{"place":2274,"value":"16283","id":"%1$s"}]},
                 "Deaths":{"entries":[{"place":8422,"value":"11300","id":"%1$s"}]},
                 "Games played":{"entries":[{"place":4253,"value":"3983","id":"%1$s"}]},
                 "Beds destroyed":{"entries":[{"place":2894,"value":"3622","id":"%1$s"}]},
                 "Highest winstreak reached":{"entries":[{"place":4766,"value":"48","id":"%1$s"}]},
                 "Melee kills":{"entries":[{"value":"11253","id":"%1$s"}]},
                 "Void kills":{"entries":[{"value":"4601","id":"%1$s"}]},
                 "Bow kills":{"entries":[{"value":"39","id":"%1$s"}]},
                 "Arrows shot":{"entries":[{"value":"920","id":"%1$s"}]},
                 "Arrows hit":{"entries":[{"value":"219","id":"%1$s"}]}}
                """.formatted(name);
        return StatsResult.parse(name.toLowerCase(), GameType.BEDWARS, json);
    }

    @Test
    void parsesCanonicalNameAndPlaces() {
        StatsResult stats = sample("Isabe5lGod4117");
        assertEquals("Isabe5lGod4117", stats.username());
        assertEquals(7509, stats.get(Stat.FINAL_KILLS));
        assertEquals(1570, stats.place(Stat.FINAL_KILLS));
        assertEquals("4.57", StatsResult.formatRatio(stats.fkdr()));
        assertEquals("1.46", StatsResult.formatRatio(stats.wlr()));
    }

    @Test
    void everyThemeAndViewFitsTheChatLimit() {
        StatsResult stats = sample("ABCDEFGHIJKLMNOP");
        ChatCharset ascii = new ChatCharset();
        ChatCharset fancy = new ChatCharset();
        fancy.allowAll("✦◆✧⚡❖☾☠≈✺♦☢☀★❄❀»┃");
        for (ChatTheme theme : ChatTheme.all()) {
            StatsFormatter formatter = new StatsFormatter(theme);
            for (StatsView view : StatsView.values()) {
                StatsRequest request = new StatsRequest("ABCDEFGHIJKLMNOP", GameType.BEDWARS, StatsPeriod.MONTHLY,
                        BedWarsMode.DOUBLES, view, false);
                for (ChatLine line : formatter.stats(request, stats)) {
                    for (ChatCharset charset : List.of(ascii, fancy)) {
                        for (int limit : new int[]{128, 128 - "/p c ".length()}) {
                            String rendered = line.render(charset, ChatFormat.ColorMode.HEX, limit);
                            assertTrue(rendered.length() <= limit, theme.id() + " " + view + ": " + rendered);
                            assertFalse(BROKEN_CODE.matcher(rendered).find(), rendered);
                            if (charset == ascii) {
                                assertTrue(rendered.chars().allMatch(c -> c >= 0x20 && c < 0x7F),
                                        "non-ASCII without permission: " + rendered);
                            }
                        }
                    }
                }
            }
            ChatLine compare = formatter.compare(new StatsRequest("ABCDEFGHIJKLMNOP", StatsPeriod.ALL_TIME,
                    BedWarsMode.ALL_MODES), stats, sample("QRSTUVWXYZ012345")).get(0);
            assertTrue(compare.render(ascii, ChatFormat.ColorMode.HEX, 123).length() <= 123);
        }
    }

    @Test
    void summaryKeepsColorsWhenItFits() {
        StatsResult stats = sample("Isabe5lGod4117");
        ChatLine line = new StatsFormatter(ChatTheme.get("midnight"))
                .stats(new StatsRequest("Isabe5lGod4117", StatsPeriod.ALL_TIME, BedWarsMode.ALL_MODES), stats).get(0);
        String rendered = line.render(new ChatCharset(), ChatFormat.ColorMode.HEX, 128);
        System.out.println(rendered.length() + " " + rendered);
        assertTrue(rendered.contains("&#"), rendered);
        assertTrue(ChatFormat.stripCodes(rendered).contains("FKDR 4.57"), rendered);
        assertTrue(ChatFormat.stripCodes(rendered).contains("WLR 1.46"), rendered);
    }

    @Test
    void legacyAndNoneModes() {
        ChatLine line = new ChatLine(ChatTheme.get("classic")).icon().name("Steve").sep().stat("FKDR", "2.00");
        assertEquals("* Steve | FKDR 2.00".replace("*", ">>"),
                ChatFormat.stripCodes(line.render(new ChatCharset(), ChatFormat.ColorMode.LEGACY, 128)));
        assertFalse(line.render(new ChatCharset(), ChatFormat.ColorMode.NONE, 128).contains("&"));
    }

    @Test
    void gradientFitsBudgetAndUsesHexCodes() {
        String text = "INCOMING TEAM RUSH";
        String rendered = ChatFormat.gradient(text, new int[]{0xffffff, 0x000000}, ChatFormat.ColorMode.HEX, 123);
        assertTrue(rendered.length() <= 123, rendered);
        assertEquals(text, ChatFormat.stripCodes(rendered));
        assertTrue(rendered.startsWith("&#ffffff"), rendered);
        String tiny = ChatFormat.gradient(text, new int[]{0xff0000, 0x0000ff}, ChatFormat.ColorMode.HEX, 40);
        assertTrue(tiny.length() <= 40, tiny);
    }

    @Test
    void componentConversionHandlesHexAndLegacy() {
        JsonObject component = ChatFormat.toComponent("&#ffffff[!!!] &aGO", true);
        String json = component.toString();
        assertTrue(json.contains("\"color\":\"#ffffff\""), json);
        assertTrue(json.contains("\"color\":\"green\""), json);
        JsonObject old = ChatFormat.toComponent("&#ff5555red", false);
        assertTrue(old.toString().contains("\"color\":\"red\""), old.toString());
    }

    @Test
    void charsetTransliteratesAndBlocksUnsafe() {
        ChatCharset charset = new ChatCharset();
        assertEquals("Cafe > x", charset.sanitize("Café » ✦x"));
        charset.allowAll("§\u0007»");
        assertFalse(charset.allows(0xA7));
        assertTrue(charset.allows('»'));
    }
}
