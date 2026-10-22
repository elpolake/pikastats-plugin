package dev.PikaStats;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class PikaStatsPunishmentsParser {
    private static final Pattern USERNAME = Pattern.compile(
            "(?is)<span\\s+class=\"username\"[^>]*>\\s*([^<]+)\\s*</span>");
    private static final Pattern ROW_START = Pattern.compile("(?is)<div\\s+class=\"row\"\\s*>");
    private static final Pattern DIV_TAG = Pattern.compile("(?is)</?div\\b[^>]*>");
    private static final DateTimeFormatter PAGE_DATE =
            DateTimeFormatter.ofPattern("MMMM d, yyyy, HH:mm", Locale.ENGLISH);
    private static final DateTimeFormatter COMPACT_DATE =
            DateTimeFormatter.ofPattern("yy-MM-dd", Locale.ROOT);

    private PikaStatsPunishmentsParser() {
    }

    static PunishmentsResult parse(String html) {
        if (html == null || !html.contains("ndzn_litebans_search")) {
            throw new IllegalArgumentException("The response was not PikaNetwork's punishments page");
        }

        Matcher usernameMatch = USERNAME.matcher(html);
        if (!usernameMatch.find()) {
            throw new PlayerNotFoundException();
        }
        String username = plainText(usernameMatch.group(1));
        if (!username.matches("[A-Za-z0-9_]{1,16}")) {
            throw new IllegalArgumentException("The punishment page contained an invalid player name");
        }

        String tbody = divContents(html, "tbody");
        return new PunishmentsResult(
                readFilterCount(html, "Bans"),
                readFilterCount(html, "Kicks"),
                readFilterCount(html, "Mutes"),
                readFilterCount(html, "Warnings"),
                readRecent(tbody));
    }

    private static List<PunishmentsResult.Entry> readRecent(String tbody) {
        if (tbody == null || tbody.isBlank()) {
            return List.of();
        }

        List<Integer> starts = new ArrayList<>();
        Matcher rowMatcher = ROW_START.matcher(tbody);
        while (rowMatcher.find()) {
            starts.add(rowMatcher.start());
        }

        List<PunishmentsResult.Entry> recent = new ArrayList<>();
        for (int index = 0; index < starts.size(); index++) {
            int start = starts.get(index);
            int end = index + 1 < starts.size() ? starts.get(index + 1) : tbody.length();
            String row = tbody.substring(start, end);
            String type = compactType(field(row, "_type"));
            String date = field(row, "_date");
            String reason = field(row, "_reason");
            if (type.isBlank()) {
                continue;
            }
            recent.add(new PunishmentsResult.Entry(
                    type,
                    compactDate(date),
                    reason));
        }
        return recent;
    }

    private static String compactType(String value) {
        String type = value.toLowerCase(Locale.ROOT);
        if (type.contains("ban")) {
            return "B";
        }
        if (type.contains("kick")) {
            return "K";
        }
        if (type.contains("mute")) {
            return "M";
        }
        if (type.contains("warn")) {
            return "W";
        }
        return "";
    }

    private static String field(String row, String suffix) {
        Pattern pattern = Pattern.compile("(?is)<div\\s+class=\"td\\s+" + Pattern.quote(suffix)
                + "\"[^>]*>(.*?)</div>");
        Matcher matcher = pattern.matcher(row);
        return matcher.find() ? plainText(matcher.group(1)) : "";
    }

    private static int readFilterCount(String html, String category) {
        Pattern pattern = Pattern.compile("(?is)My\\s+" + Pattern.quote(category) + "\\s*\\((\\d+)\\)");
        Matcher matcher = pattern.matcher(html);
        if (!matcher.find()) {
            return 0;
        }
        try {
            return Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static String divContents(String html, String className) {
        Pattern openingDiv = Pattern.compile("(?is)<div\\b[^>]*class=\"[^\"]*\\b"
                + Pattern.quote(className) + "\\b[^\"]*\"[^>]*>");
        Matcher opening = openingDiv.matcher(html);
        if (!opening.find()) {
            return null;
        }

        int contentStart = opening.end();
        int depth = 1;
        Matcher tags = DIV_TAG.matcher(html);
        tags.region(contentStart, html.length());
        while (tags.find()) {
            String tag = tags.group();
            if (tag.startsWith("</")) {
                depth--;
                if (depth == 0) {
                    return html.substring(contentStart, tags.start());
                }
            } else if (!tag.endsWith("/>")) {
                depth++;
            }
        }
        return null;
    }

    private static String plainText(String html) {
        String text = html.replaceAll("(?is)<[^>]*>", " ");
        text = text.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'").replace("&nbsp;", " ");
        text = text.replaceAll("(?i)[§&][0-9A-FK-ORX]", "");
        return text.replaceAll("\\s+", " ").trim();
    }

    private static String compactDate(String value) {
        try {
            return LocalDateTime.parse(value, PAGE_DATE).format(COMPACT_DATE);
        } catch (DateTimeParseException ignored) {
            return "";
        }
    }

    static final class PlayerNotFoundException extends RuntimeException {
    }
}
