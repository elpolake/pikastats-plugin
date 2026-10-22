package dev.PikaStats;

import java.util.List;
import java.util.regex.Pattern;

record PunishmentsResult(
        int bans,
        int kicks,
        int mutes,
        int warnings,
        List<Entry> entries) {
    private static final int MAX_CHAT_LENGTH = 100;
    private static final int MAX_RECENT = 3;
    private static final Pattern URL = Pattern.compile(
            "(?i)\\b(https?)://([a-z0-9-]+)\\.([a-z]{2,})(/[^\\s]*)?");

    PunishmentsResult {
        entries = List.copyOf(entries);
    }

    String countsMessage() {
        return "bans: " + bans + " | mutes: " + mutes + " | warns: " + warnings + " | kicks: " + kicks;
    }

    String compactMessage() {
        StringBuilder message = new StringBuilder(countsMessage());
        int appended = 0;
        for (Entry entry : entries) {
            if (appended >= MAX_RECENT) {
                break;
            }
            String item = entry.compactText();
            if (message.length() + 3 + item.length() > MAX_CHAT_LENGTH) {
                break;
            }
            message.append(" | ").append(item);
            appended++;
        }
        return message.toString();
    }

    List<String> reasonMessages() {
        List<String> messages = new java.util.ArrayList<>();
        messages.add(countsMessage());
        for (Entry entry : entries) {
            String typeName = switch (entry.type()) {
                case "B" -> "ban";
                case "K" -> "kick";
                case "M" -> "mute";
                case "W" -> "warn";
                default -> entry.type();
            };
            String prefix = entry.date().isBlank()
                    ? typeName
                    : typeName + " " + entry.date();
            String reason = entry.reason().isBlank() ? "No reason listed" : redactFilteredWords(entry.reason());
            String reasonPrefix = prefix + ": ";
            int room = Math.max(0, MAX_CHAT_LENGTH - reasonPrefix.length());
            if (reason.length() > room) {
                reason = reason.substring(0, room).stripTrailing();
            }
            messages.add(reasonPrefix + reason);
        }
        if (messages.size() == 1) {
            messages.add("No punishments found.");
        }
        return List.copyOf(messages);
    }

    private static String redactFilteredWords(String reason) {
        String safeReason = reason
                .replaceAll("(?i)\\brape\\b", "R-word");
        return URL.matcher(safeReason).replaceAll("$1 ://$2 dot $3$4");
    }

    record Entry(String type, String date, String reason) {
        String compactText() {
            return date.isBlank() ? type : type + " " + date;
        }
    }
}
