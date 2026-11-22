package dev.PikaStats;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Pattern;

record PunishmentsResult(String username, int bans, int kicks, int mutes, int warnings, List<Entry> entries) {
    private static final Pattern URL = Pattern.compile("(?i)\\b(https?)://([a-z0-9-]+)\\.([a-z]{2,})(/\\S*)?");
    private static final Pattern DOMAIN = Pattern.compile("(?i)\\b([a-z0-9-]{2,}\\.)+(com|net|org|gg|io|xyz|me|cc|tk|ru|sc|be|pics|co|de|uk|us)\\b(/\\S*)?");

    PunishmentsResult {
        entries = List.copyOf(entries);
    }

    static PunishmentsResult combine(PunishmentsResult all, PunishmentsResult... filtered) {
        LinkedHashSet<Entry> unique = new LinkedHashSet<>(all.entries());
        for (PunishmentsResult result : filtered) {
            unique.addAll(result.entries());
        }
        return new PunishmentsResult(all.username(), all.bans(), all.kicks(), all.mutes(), all.warnings(), new ArrayList<>(unique));
    }

    List<Entry> activePunishments() {
        return this.entries.stream().filter(Entry::active).toList();
    }

    int total() {
        return this.bans + this.kicks + this.mutes + this.warnings;
    }

    /**
     * Makes a server-provided reason safe for public chat: words the chat filter blocks are
     * redacted and links are broken up so they are not treated as advertising.
     */
    static String safeReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return "No reason listed";
        }
        String safe = reason.replaceAll("(?i)\\brape\\b", "R-word");
        // PikaNetwork's filter blocks links, even spelled out ("prnt dot sc"), so drop them entirely.
        safe = URL.matcher(safe).replaceAll("[link]");
        safe = DOMAIN.matcher(safe).replaceAll("[link]");
        safe = safe.replaceAll("(?i)\\b[a-z0-9-]{2,}\\s+dot\\s+[a-z]{2,6}\\b", "[link]");
        safe = safe.replaceAll("(?i)\\b(prnt|imgur|youtu|lunr|gyazo|discord)\\.?\\s*[a-z]{0,3}\\b", "[link]")
                .replaceAll("\\s{2,}", " ").strip();
        return safe; // Long reasons are split over several messages by Reply.
    }

    record Entry(String type, String date, String reason, String expires, boolean active) {
    }
}
