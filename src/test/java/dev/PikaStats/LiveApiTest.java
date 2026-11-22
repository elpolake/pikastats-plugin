package dev.PikaStats;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Talks to the real PikaNetwork API. Runs only with PIKA_STATS_API_KEY set; print the rendered chat
 * lines with {@code gradlew test -PshowOutput}. Override the player with PIKA_STATS_TEST_PLAYER.
 */
@EnabledIfEnvironmentVariable(named = "PIKA_STATS_API_KEY", matches = ".+")
class LiveApiTest {
    private static final String PLAYER = System.getenv().getOrDefault("PIKA_STATS_TEST_PLAYER", "isabe5lgod4117");

    @Test
    void rendersRealStatsInEveryStyle() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).executor(executor).build();
            ApiKeyPool pool = new ApiKeyPool(client);
            pool.setKeys(List.of(System.getenv("PIKA_STATS_API_KEY").split(",")));
            PikaStatsApi api = new PikaStatsApi(client, executor, pool);
            StatsRequest overall = StatsRequest.parseArguments(GameType.BEDWARS, List.of(PLAYER));
            StatsResult stats = api.lookup(overall).get(20, TimeUnit.SECONDS);
            ProfileResult profile = api.lookupProfile(PLAYER).get(20, TimeUnit.SECONDS);
            PunishmentsResult punishments = api.lookupPunishments(PLAYER).get(30, TimeUnit.SECONDS);
            StatsResult weekly = api.lookup(StatsRequest.parseArguments(GameType.BEDWARS,
                    List.of(PLAYER, "weekly", "quads"))).get(20, TimeUnit.SECONDS);
            StatsResult skywars = api.lookup(StatsRequest.parseArguments(GameType.SKYWARS, List.of(PLAYER)))
                    .get(20, TimeUnit.SECONDS);
            assertTrue(stats.get(Stat.GAMES) > 0, "expected games for " + PLAYER);
            assertEquals(PLAYER.toLowerCase(), stats.username().toLowerCase());
            // Second lookup of the same scope must come from the cache, not the API.
            long sentBefore = pool.sent();
            api.lookup(overall).get(5, TimeUnit.SECONDS);
            assertEquals(sentBefore, pool.sent());
            System.out.println("API requests: " + pool.sent() + ", quota left: " + pool.totalRemaining() + "/" + pool.totalLimit());

            ChatCharset charset = new ChatCharset();
            for (ChatTheme theme : ChatTheme.all()) {
                StatsFormatter formatter = new StatsFormatter(theme);
                System.out.println("==== " + theme.id());
                print(formatter.stats(overall, stats), charset);
                if (theme.id().equals(ChatTheme.DEFAULT_ID)) {
                    for (String view : List.of("full", "lb", "combat", "fkdr", "wins", "ws", "wr")) {
                        StatsRequest request = StatsRequest.parseArguments(GameType.BEDWARS, List.of(PLAYER, view));
                        print(formatter.stats(request, stats), charset);
                    }
                    print(formatter.stats(StatsRequest.parseArguments(GameType.BEDWARS,
                            List.of(PLAYER, "weekly", "quads")), weekly), charset);
                    print(formatter.stats(StatsRequest.parseArguments(GameType.SKYWARS, List.of(PLAYER)), skywars),
                            charset);
                    print(formatter.profile(profile), charset);
                    print(formatter.punishments(PLAYER, punishments, false, false), charset);
                    print(formatter.punishments(PLAYER, punishments, true, false), charset);
                    print(formatter.compare(overall, stats, weekly), charset);
                }
            }
        } finally {
            executor.shutdownNow();
        }
    }

    private static void print(List<ChatLine> lines, ChatCharset charset) {
        for (ChatLine line : lines) {
            String rendered = line.render(charset, ChatFormat.ColorMode.HEX, 128);
            assertTrue(rendered.length() <= 128, rendered);
            System.out.printf("%3d | %s%n    | %s%n", rendered.length(), rendered, ChatFormat.stripCodes(rendered));
        }
    }
}
