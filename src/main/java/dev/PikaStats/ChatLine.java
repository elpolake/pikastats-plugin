package dev.PikaStats;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Builds one themed chat message from role-tagged pieces and renders it into the server's
 * character budget. When the colored line is too long it first merges colors that look alike,
 * then drops optional groups (highest priority number first), then removes colors, and only
 * truncates as a last resort.
 */
final class ChatLine {
    enum Role { ICON, NAME, TEXT, LABEL, SEP, MUTED, VALUE }

    private record Segment(String text, String fallback, Role role, String color, int group, int priority) {
    }

    private final ChatTheme theme;
    private final List<Segment> segments = new ArrayList<>();
    /** Long free text (punishment reasons, help) is split over several messages instead of trimmed. */
    private boolean splittable;
    private int currentGroup;
    private int currentPriority;
    private int nextGroup = 1;

    ChatLine(ChatTheme theme) {
        this.theme = theme;
    }

    ChatTheme theme() {
        return this.theme;
    }

    ChatLine icon() {
        return add(this.theme.icon(), this.theme.iconFallback(), Role.ICON, this.theme.accent()).text(" ");
    }

    ChatLine sep() {
        return add(" " + this.theme.separator() + " ", " " + this.theme.separatorFallback() + " ", Role.SEP,
                this.theme.muted());
    }

    /** A glyph with an ASCII fallback, used when the server does not allow the glyph. */
    ChatLine glyph(String preferred, String fallback, Role role, String color) {
        return add(preferred, fallback, role, color);
    }

    ChatLine name(String text) {
        return add(text, null, Role.NAME, this.theme.name());
    }

    ChatLine text(String text) {
        return add(text, null, Role.TEXT, this.theme.text());
    }

    ChatLine label(String text) {
        return add(text, null, Role.LABEL, this.theme.muted());
    }

    ChatLine muted(String text) {
        return add(text, null, Role.MUTED, this.theme.muted());
    }

    ChatLine accent(String text) {
        return add(text, null, Role.ICON, this.theme.accent());
    }

    ChatLine value(String text) {
        return add(text, null, Role.VALUE, this.theme.value());
    }

    ChatLine value(String text, String color) {
        return add(text, null, Role.VALUE, color);
    }

    ChatLine tier(ChatTheme.Tier tier, double value, String formatted) {
        return add(formatted, null, Role.VALUE, this.theme.tierColor(tier, value));
    }

    ChatLine good(String text) {
        return add(text, null, Role.VALUE, this.theme.good());
    }

    ChatLine bad(String text) {
        return add(text, null, Role.VALUE, this.theme.bad());
    }

    ChatLine warn(String text) {
        return add(text, null, Role.VALUE, this.theme.warn());
    }

    /** "label value" pair, with the label muted and the value tier-colored. */
    ChatLine stat(String label, ChatTheme.Tier tier, double value, String formatted) {
        return label(label + " ").tier(tier, value, formatted);
    }

    ChatLine stat(String label, String formatted) {
        return label(label + " ").value(formatted);
    }

    /**
     * Adds pieces that may be dropped when the line is too long. Larger priority numbers are
     * dropped first.
     */
    ChatLine optional(int priority, Consumer<ChatLine> content) {
        int previousGroup = this.currentGroup;
        int previousPriority = this.currentPriority;
        this.currentGroup = this.nextGroup++;
        this.currentPriority = priority;
        content.accept(this);
        this.currentGroup = previousGroup;
        this.currentPriority = previousPriority;
        return this;
    }

    private ChatLine add(String text, String fallback, Role role, String color) {
        if (text != null && !text.isEmpty()) {
            this.segments.add(new Segment(text, fallback, role, color, this.currentGroup, this.currentPriority));
        }
        return this;
    }

    ChatLine splittable() {
        this.splittable = true;
        return this;
    }

    boolean isSplittable() {
        return this.splittable;
    }

    /** Icon and name in the accent color: one color code for both. */
    ChatLine head(String name) {
        return glyph(this.theme.icon(), this.theme.iconFallback(), Role.NAME, this.theme.accent())
                .add(" " + name, null, Role.NAME, this.theme.accent());
    }

    /** Just the icon, for continuation lines. */
    ChatLine iconOnly() {
        return glyph(this.theme.icon(), this.theme.iconFallback(), Role.NAME, this.theme.accent());
    }

    /** Plain text in an explicit color (no separator). */
    ChatLine add2(String text, String color) {
        return add(text, null, Role.VALUE, color);
    }

    /** " | label value" entirely in {@code color} (one color code). */
    ChatLine piece(String label, String value, String color) {
        glyph(" " + this.theme.separator() + " ", " " + this.theme.separatorFallback() + " ", Role.VALUE, color);
        return add(label.isEmpty() ? value : label + " " + value, null, Role.VALUE, color);
    }

    ChatLine piece(int priority, String label, String value, String color) {
        if (priority <= 0) {
            return piece(label, value, color);
        }
        return optional(priority, l -> l.piece(label, value, color));
    }

    /**
     * Keeps colors first: drops optional pieces (largest priority first) before merging any colors,
     * and only then falls back to {@link #render}.
     */
    String renderColorsFirst(ChatCharset charset, ChatFormat.ColorMode mode, int limit) {
        if (this.theme.colorless() || mode == ChatFormat.ColorMode.NONE) {
            return render(charset, ChatFormat.ColorMode.NONE, limit);
        }
        List<Integer> optionalGroups = optionalGroupsByDropOrder();
        for (int dropped = 0; dropped <= optionalGroups.size(); dropped++) {
            String rendered = renderAt(charset, mode, 0, optionalGroups.subList(0, dropped));
            if (rendered.length() <= limit) {
                return rendered;
            }
        }
        return render(charset, mode, limit);
    }

    boolean isEmpty() {
        return this.segments.isEmpty();
    }

    /** Plain text without any color codes, using allowed glyphs. */
    String plain(ChatCharset charset) {
        StringBuilder output = new StringBuilder();
        for (Segment segment : this.segments) {
            output.append(resolve(segment, charset));
        }
        return output.toString();
    }

    /** Formatted text without any length limit, e.g. for client-only messages. */
    String renderUnlimited(ChatCharset charset, ChatFormat.ColorMode mode) {
        return render(charset, mode == ChatFormat.ColorMode.NONE ? mode : ChatFormat.ColorMode.HEX, Integer.MAX_VALUE);
    }

    String render(ChatCharset charset, ChatFormat.ColorMode mode, int limit) {
        if (this.theme.colorless()) {
            mode = ChatFormat.ColorMode.NONE;
        }
        List<Integer> optionalGroups = optionalGroupsByDropOrder();
        for (int dropped = 0; dropped <= optionalGroups.size(); dropped++) {
            List<Integer> removed = optionalGroups.subList(0, dropped);
            int maxLevel = mode == ChatFormat.ColorMode.NONE ? 0 : 2;
            for (int level = 0; level <= maxLevel; level++) {
                String rendered = renderAt(charset, mode, level, removed);
                if (rendered.length() <= limit) {
                    return rendered;
                }
            }
        }
        if (mode != ChatFormat.ColorMode.NONE) {
            for (int level = 3; level <= 4; level++) {
                String rendered = renderAt(charset, mode, level, optionalGroups);
                if (rendered.length() <= limit) {
                    return rendered;
                }
            }
        }
        String plain = renderAt(charset, ChatFormat.ColorMode.NONE, 0, optionalGroups);
        if (plain.length() <= limit) {
            return plain;
        }
        int end = Math.max(0, limit - 2);
        if (end > 0 && Character.isHighSurrogate(plain.charAt(end - 1))) {
            end--;
        }
        return plain.substring(0, end).stripTrailing() + "..";
    }

    /**
     * Renders everything (optional parts included) and, when it is longer than {@code limit}, splits
     * it into several messages at the " | " separators. Continuation messages start with the icon
     * again. A single piece that is still too long is word-wrapped.
     */
    List<String> renderSplit(ChatCharset charset, ChatFormat.ColorMode mode, int limit) {
        if (this.theme.colorless()) {
            mode = ChatFormat.ColorMode.NONE;
        }
        String whole = fit(this.segments, charset, mode, limit);
        if (whole != null) {
            return List.of(whole);
        }
        List<List<Segment>> chunks = new ArrayList<>();
        List<Segment> chunk = new ArrayList<>();
        for (Segment segment : this.segments) {
            if (segment.role() == Role.SEP && !chunk.isEmpty()) {
                chunks.add(chunk);
                chunk = new ArrayList<>();
            }
            chunk.add(segment);
        }
        if (!chunk.isEmpty()) {
            chunks.add(chunk);
        }
        List<Segment> continuation = new ArrayList<>();
        for (Segment segment : this.segments) {
            if (segment.role() == Role.ICON || (segment.role() == Role.TEXT && segment.text().isBlank())) {
                continuation.add(segment);
            } else {
                break;
            }
        }
        List<String> messages = new ArrayList<>();
        List<Segment> current = new ArrayList<>();
        for (List<Segment> next : chunks) {
            List<Segment> candidate = new ArrayList<>(current);
            candidate.addAll(next);
            if (current.isEmpty() || fit(candidate, charset, mode, limit) != null) {
                current = candidate;
                continue;
            }
            messages.addAll(wrap(current, charset, mode, limit));
            current = new ArrayList<>(continuation);
            current.addAll(next.get(0).role() == Role.SEP ? next.subList(1, next.size()) : next);
        }
        if (!current.isEmpty()) {
            messages.addAll(wrap(current, charset, mode, limit));
        }
        return messages;
    }

    /** The line at the best palette level that fits, or null. */
    private String fit(List<Segment> parts, ChatCharset charset, ChatFormat.ColorMode mode, int limit) {
        int maxLevel = mode == ChatFormat.ColorMode.NONE ? 0 : 2;
        for (int level = 0; level <= maxLevel; level++) {
            String rendered = renderSegments(parts, charset, mode, level);
            if (rendered.length() <= limit) {
                return rendered;
            }
        }
        return null;
    }

    private List<String> wrap(List<Segment> parts, ChatCharset charset, ChatFormat.ColorMode mode, int limit) {
        String fitted = fit(parts, charset, mode, limit);
        if (fitted != null) {
            return List.of(fitted);
        }
        // One piece longer than a whole message (e.g. a long punishment reason): wrap its words.
        String code = mode == ChatFormat.ColorMode.NONE ? "" : ChatFormat.code(this.theme.text(), mode);
        int room = Math.max(10, limit - code.length());
        String text = renderSegments(parts, charset, ChatFormat.ColorMode.NONE, 0);
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            while (word.length() > room) {
                if (line.length() > 0) {
                    lines.add(code + line);
                    line.setLength(0);
                }
                lines.add(code + word.substring(0, room));
                word = word.substring(room);
            }
            if (line.length() > 0 && line.length() + 1 + word.length() > room) {
                lines.add(code + line);
                line.setLength(0);
            }
            if (line.length() > 0) {
                line.append(' ');
            }
            line.append(word);
        }
        if (line.length() > 0) {
            lines.add(code + line);
        }
        return lines;
    }

    private String renderSegments(List<Segment> parts, ChatCharset charset, ChatFormat.ColorMode mode, int level) {
        StringBuilder output = new StringBuilder();
        String currentColor = null;
        for (Segment segment : parts) {
            String text = resolve(segment, charset);
            if (text.isEmpty()) {
                continue;
            }
            if (mode != ChatFormat.ColorMode.NONE && !text.isBlank()) {
                String code = ChatFormat.code(colorAt(segment, level), mode);
                if (!code.equals(currentColor)) {
                    output.append(code);
                    currentColor = code;
                }
            }
            output.append(text);
        }
        return output.toString().strip();
    }

    private List<Integer> optionalGroupsByDropOrder() {
        List<int[]> groups = new ArrayList<>();
        for (Segment segment : this.segments) {
            if (segment.group() == 0) {
                continue;
            }
            boolean known = groups.stream().anyMatch(group -> group[0] == segment.group());
            if (!known) {
                groups.add(new int[]{segment.group(), segment.priority()});
            }
        }
        // Highest priority number first; later groups first among equals so lines lose their tail.
        groups.sort((first, second) -> first[1] != second[1]
                ? Integer.compare(second[1], first[1])
                : Integer.compare(second[0], first[0]));
        List<Integer> order = new ArrayList<>();
        groups.forEach(group -> order.add(group[0]));
        return order;
    }

    /**
     * Palette levels: 0 full colors; 1 icon shares the name color; 2 body text shares the muted
     * color; 3 values also share the muted color; 4 everything one color.
     */
    private String renderAt(ChatCharset charset, ChatFormat.ColorMode mode, int level, List<Integer> removedGroups) {
        StringBuilder output = new StringBuilder();
        String currentColor = null;
        for (Segment segment : this.segments) {
            if (segment.group() != 0 && removedGroups.contains(segment.group())) {
                continue;
            }
            String text = resolve(segment, charset);
            if (text.isEmpty()) {
                continue;
            }
            if (mode != ChatFormat.ColorMode.NONE && !text.isBlank()) {
                String color = colorAt(segment, level);
                String code = ChatFormat.code(color, mode);
                if (!code.equals(currentColor)) {
                    output.append(code);
                    currentColor = code;
                }
            }
            output.append(text);
        }
        return output.toString().stripTrailing();
    }

    private String colorAt(Segment segment, int level) {
        if (level >= 4) {
            return this.theme.name();
        }
        Role role = segment.role();
        if (level >= 1 && role == Role.ICON) {
            return this.theme.name();
        }
        if (level >= 2 && (role == Role.TEXT || role == Role.LABEL || role == Role.SEP)) {
            return this.theme.muted();
        }
        if (level >= 3 && role == Role.VALUE) {
            return this.theme.muted();
        }
        return segment.color();
    }

    private static String resolve(Segment segment, ChatCharset charset) {
        String text = segment.fallback() == null
                ? segment.text()
                : charset.glyph(segment.text(), segment.fallback());
        return charset.sanitize(text);
    }
}
