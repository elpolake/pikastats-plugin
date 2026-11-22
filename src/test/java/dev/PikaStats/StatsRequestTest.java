package dev.PikaStats;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StatsRequestTest {
    private static StatsRequest parse(String... args) {
        return StatsRequest.parseArguments(GameType.BEDWARS, List.of(args));
    }

    @Test
    void defaultsToLifetimeOverallSummary() {
        StatsRequest request = parse("Isabe5lGod4117");
        assertNotNull(request);
        assertEquals(StatsPeriod.ALL_TIME, request.period());
        assertEquals(BedWarsMode.ALL_MODES, request.mode());
        assertEquals(StatsView.SUMMARY, request.view());
    }

    @Test
    void keepsLegacyPeriodModeSyntax() {
        StatsRequest request = parse("Steve", "w", "4");
        assertEquals(StatsPeriod.WEEKLY, request.period());
        assertEquals(BedWarsMode.QUADS, request.mode());
        StatsRequest allAll = parse("Steve", "a", "a", "fkdr");
        assertEquals(StatsPeriod.ALL_TIME, allAll.period());
        assertEquals(BedWarsMode.ALL_MODES, allAll.mode());
        assertEquals(StatsView.FKDR, allAll.view());
    }

    @Test
    void acceptsArgumentsInAnyOrder() {
        StatsRequest request = parse("Steve", "fkdr", "duos", "monthly", "q");
        assertEquals(StatsView.FKDR, request.view());
        assertEquals(BedWarsMode.DOUBLES, request.mode());
        assertEquals(StatsPeriod.MONTHLY, request.period());
        assertTrue(request.quiet());
        assertEquals(BedWarsMode.SOLO, parse("Steve", "solo").mode());
        assertEquals(BedWarsMode.TRIPLES, parse("Steve", "3s").mode());
        assertEquals(StatsView.FULL, parse("Steve", "full").view());
        assertEquals(StatsView.PLACES, parse("Steve", "lb").view());
    }

    @Test
    void rejectsTyposAndBadNames() {
        assertNull(parse("Steve", "fkdrr"));
        assertNull(parse("not-a-name"));
        assertNull(parse("Steve", "w", "w"));
        assertNull(StatsRequest.parseArguments(GameType.SKYWARS, List.of("Steve", "quads")));
    }

    @Test
    void scopeLabels() {
        assertEquals("BW Overall", parse("Steve").scopeLabel());
        assertEquals("BW Weekly Quads", parse("Steve", "w", "4").scopeLabel());
        assertEquals("BW Monthly All", parse("Steve", "m").scopeLabel());
        assertEquals("SW Solo", StatsRequest.parseArguments(GameType.SKYWARS, List.of("Steve", "solo")).scopeLabel());
    }
}
