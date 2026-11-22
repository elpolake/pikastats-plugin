package dev.PikaStats;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A chat color style. {@code tiers} run from "low" to "insane" and color ratio values; glyphs are
 * only used when the server allows them (see {@link ChatCharset}), otherwise the ASCII fallback is.
 */
record ChatTheme(
        String id,
        String description,
        boolean dark,
        String accent,
        String name,
        String text,
        String muted,
        String value,
        String good,
        String bad,
        String warn,
        String[] tiers,
        int[] gradient,
        String icon,
        String iconFallback,
        String separator,
        String separatorFallback) {

    private static final Map<String, ChatTheme> THEMES = new LinkedHashMap<>();

    static {
        register(new ChatTheme("midnight", "Dark modern violet (default)", true,
                "#a78bfa", "#f5f3ff", "#c9c6e0", "#8a87a8", "#ffffff", "#4ade80", "#f87171", "#fbbf24",
                tiers("#9ca3af", "#e5e7eb", "#facc15", "#fb923c", "#f43f5e", "#d946ef"),
                stops(0xc4b5fd, 0x8b5cf6, 0x6d28d9), "✦", "*", "┃", "|"));
        register(new ChatTheme("obsidian", "Monochrome dark, white accents", true,
                "#ffffff", "#f4f4f5", "#b4b4bb", "#71717a", "#ffffff", "#d4d4d8", "#71717a", "#a1a1aa",
                tiers("#71717a", "#a1a1aa", "#d4d4d8", "#e4e4e7", "#f4f4f5", "#ffffff"),
                stops(0xffffff, 0x71717a), "◆", "*", "┃", "|"));
        register(new ChatTheme("eclipse", "White to black fade, like &#ffffff[!!!] &#555555IN&#222222CO", true,
                "#ffffff", "#dddddd", "#bbbbbb", "#666666", "#ffffff", "#ffffff", "#555555", "#888888",
                tiers("#555555", "#777777", "#999999", "#bbbbbb", "#dddddd", "#ffffff"),
                stops(0xffffff, 0xbbbbbb, 0x555555, 0x222222), "[!!!]", "[!]", "┃", "|"));
        register(new ChatTheme("void", "Deep purple and black", true,
                "#9d4edd", "#e0aaff", "#c77dff", "#5a189a", "#f3e8ff", "#c77dff", "#7b2cbf", "#e0aaff",
                tiers("#7b2cbf", "#9d4edd", "#c77dff", "#e0aaff", "#f3e8ff", "#ffffff"),
                stops(0xe0aaff, 0x9d4edd, 0x3c096c), "✧", "*", "┃", "|"));
        register(new ChatTheme("neon", "Cyberpunk cyan and magenta", true,
                "#ff2bd6", "#00f0ff", "#b8b8ff", "#7a6fb0", "#faff00", "#39ff14", "#ff3860", "#ff9f1c",
                tiers("#8a8aa8", "#00f0ff", "#39ff14", "#faff00", "#ff6a00", "#ff2bd6"),
                stops(0x00f0ff, 0xff2bd6), "⚡", "//", "┃", "|"));
        register(new ChatTheme("tokyo", "Tokyo Night blues", true,
                "#7aa2f7", "#c0caf5", "#a9b1d6", "#565f89", "#ffffff", "#9ece6a", "#f7768e", "#e0af68",
                tiers("#565f89", "#a9b1d6", "#9ece6a", "#e0af68", "#ff9e64", "#f7768e"),
                stops(0x7dcfff, 0x7aa2f7, 0xbb9af7), "❖", "*", "┃", "|"));
        register(new ChatTheme("dracula", "Dracula purple and pastel", true,
                "#bd93f9", "#f8f8f2", "#e2e2dc", "#6272a4", "#50fa7b", "#50fa7b", "#ff5555", "#f1fa8c",
                tiers("#6272a4", "#f8f8f2", "#50fa7b", "#f1fa8c", "#ffb86c", "#ff5555"),
                stops(0xff79c6, 0xbd93f9), "☾", "*", "┃", "|"));
        register(new ChatTheme("crimson", "Blood red on dark", true,
                "#ef4444", "#fee2e2", "#e8b4b4", "#a16060", "#ffffff", "#fca5a5", "#ef4444", "#fbbf24",
                tiers("#a8a29e", "#fde68a", "#fca5a5", "#f87171", "#ef4444", "#ff1f4b"),
                stops(0xfca5a5, 0xdc2626, 0x7f1d1d), "☠", "x", "┃", "|"));
        register(new ChatTheme("ocean", "Deep sea blue and teal", true,
                "#38bdf8", "#e0f2fe", "#a5c4dc", "#64748b", "#f0f9ff", "#2dd4bf", "#fb7185", "#fbbf24",
                tiers("#94a3b8", "#7dd3fc", "#38bdf8", "#22d3ee", "#2dd4bf", "#a78bfa"),
                stops(0x7dd3fc, 0x0ea5e9, 0x0369a1), "≈", "~", "┃", "|"));
        register(new ChatTheme("aurora", "Northern lights teal to violet", true,
                "#5eead4", "#f0fdfa", "#c4b5fd", "#7c8aa5", "#ffffff", "#86efac", "#fb7185", "#fde047",
                tiers("#94a3b8", "#5eead4", "#86efac", "#fde047", "#f0abfc", "#c084fc"),
                stops(0x5eead4, 0x818cf8, 0xc084fc), "✺", "*", "┃", "|"));
        register(new ChatTheme("emerald", "Emerald green", true,
                "#34d399", "#ecfdf5", "#a7f3d0", "#6b8f80", "#ffffff", "#4ade80", "#f87171", "#facc15",
                tiers("#9ca3af", "#a7f3d0", "#6ee7b7", "#facc15", "#fb923c", "#f43f5e"),
                stops(0x6ee7b7, 0x10b981, 0x047857), "♦", "*", "┃", "|"));
        register(new ChatTheme("toxic", "Acid lime", true,
                "#a3e635", "#f7fee7", "#d9f99d", "#6b7d52", "#ffffff", "#a3e635", "#ef4444", "#facc15",
                tiers("#9ca3af", "#d9f99d", "#a3e635", "#facc15", "#f97316", "#ef4444"),
                stops(0xd9f99d, 0x84cc16, 0x3f6212), "☢", "!", "┃", "|"));
        register(new ChatTheme("sunset", "Warm orange to pink", true,
                "#fb7185", "#fff1f2", "#fdba74", "#9f7a8a", "#fff7ed", "#fdba74", "#e11d48", "#fde68a",
                tiers("#a8a29e", "#fde68a", "#fdba74", "#fb923c", "#f472b6", "#e11d48"),
                stops(0xfde68a, 0xfb923c, 0xf43f5e), "☀", "*", "┃", "|"));
        register(new ChatTheme("gold", "Royal gold", true,
                "#fbbf24", "#fffbeb", "#fcd34d", "#92806a", "#ffffff", "#fde68a", "#dc2626", "#f59e0b",
                tiers("#a8a29e", "#fde68a", "#fbbf24", "#f59e0b", "#ea580c", "#dc2626"),
                stops(0xfff1b8, 0xfbbf24, 0xb45309), "★", "*", "┃", "|"));
        register(new ChatTheme("frost", "Icy pale blue", false,
                "#bae6fd", "#ffffff", "#cfe8ff", "#7393b3", "#ffffff", "#a5f3fc", "#fda4af", "#fef08a",
                tiers("#94a3b8", "#cfe8ff", "#93c5fd", "#60a5fa", "#818cf8", "#c4b5fd"),
                stops(0xffffff, 0xbae6fd, 0x60a5fa), "❄", "*", "┃", "|"));
        register(new ChatTheme("sakura", "Cherry blossom pink", false,
                "#f9a8d4", "#fdf2f8", "#fbcfe8", "#a37d92", "#ffffff", "#f9a8d4", "#e11d48", "#fde68a",
                tiers("#a8a29e", "#fbcfe8", "#f9a8d4", "#f472b6", "#ec4899", "#db2777"),
                stops(0xfdf2f8, 0xf9a8d4, 0xdb2777), "❀", "*", "┃", "|"));
        register(new ChatTheme("classic", "Classic Minecraft colors", false,
                "#ffaa00", "#ffffff", "#aaaaaa", "#555555", "#ffff55", "#55ff55", "#ff5555", "#ffaa00",
                tiers("#aaaaaa", "#ffffff", "#ffff55", "#ffaa00", "#ff5555", "#aa00aa"),
                stops(0xffff55, 0xffaa00), "»", ">>", "┃", "|"));
        register(new ChatTheme("plain", "No colors, just text", false,
                "#ffffff", "#ffffff", "#ffffff", "#ffffff", "#ffffff", "#ffffff", "#ffffff", "#ffffff",
                tiers("#ffffff", "#ffffff", "#ffffff", "#ffffff", "#ffffff", "#ffffff"),
                stops(0xffffff), "»", ">", "|", "|"));
    }

    static final String DEFAULT_ID = "midnight";

    private static void register(ChatTheme theme) {
        THEMES.put(theme.id(), theme);
    }

    private static String[] tiers(String... colors) {
        return colors;
    }

    private static int[] stops(int... colors) {
        return colors;
    }

    static ChatTheme get(String id) {
        if (id == null) {
            return THEMES.get(DEFAULT_ID);
        }
        return THEMES.getOrDefault(id.toLowerCase(Locale.ROOT).trim(), THEMES.get(DEFAULT_ID));
    }

    static ChatTheme find(String id) {
        return id == null ? null : THEMES.get(id.toLowerCase(Locale.ROOT).trim());
    }

    static List<ChatTheme> all() {
        return new ArrayList<>(THEMES.values());
    }

    boolean colorless() {
        return "plain".equals(this.id);
    }

    /**
     * A distinct color for the n-th stat of a line (cycles). Every stat gets its own color, and the
     * " | " before it shares that color, so each stat costs a single 8-character color code.
     */
    String statColor(int index) {
        String[] colors = {this.tiers[2], this.tiers[4], this.good, this.tiers[3], this.tiers[5], this.warn, this.value};
        return colors[Math.floorMod(index, colors.length)];
    }

    String tierColor(Tier tier, double value) {
        return this.tiers[Math.max(0, Math.min(this.tiers.length - 1, tier.index(value)))];
    }

    /** Thresholds that decide which tier color a value gets. */
    enum Tier {
        FKDR(1, 2, 4, 7, 12),
        WLR(0.5, 1, 2, 4, 8),
        KDR(1, 1.5, 2.5, 4, 6),
        BBLR(0.5, 1, 2, 3, 5),
        WINRATE(30, 45, 60, 75, 88),
        LEVEL(20, 40, 60, 80, 100),
        THREAT(3, 5, 8, 12, 17);

        private final double[] thresholds;

        Tier(double... thresholds) {
            this.thresholds = thresholds;
        }

        int index(double value) {
            int index = 0;
            for (double threshold : this.thresholds) {
                if (value >= threshold) {
                    index++;
                }
            }
            return index;
        }

        String label(double value) {
            return switch (index(value)) {
                case 0 -> "Low";
                case 1 -> "Decent";
                case 2 -> "Good";
                case 3 -> "Strong";
                case 4 -> "Sweaty";
                default -> "Insane";
            };
        }
    }
}
