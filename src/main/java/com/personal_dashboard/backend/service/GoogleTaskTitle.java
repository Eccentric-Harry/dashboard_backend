package com.personal_dashboard.backend.service;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Encodes a task's time of day into its Google Tasks title, and decodes it back.
 *
 * <p><b>Why this exists.</b> The Tasks API has no field for a time of day: {@code due}
 * is documented as an RFC 3339 timestamp but Google discards the time portion on write.
 * So a task due at 13:00 arrives on the phone widget showing only "Tomorrow". The only
 * places a time can survive are the title and the notes, and the widget truncates
 * aggressively — a suffix gets cut off, and notes may not be shown at all. A prefix on
 * the title is the one position that is always visible.
 *
 * <p><b>Why it round-trips.</b> Writing the prefix without reading it back would mean
 * every pull re-prefixed an already-prefixed title ("13:00 · 13:00 · Lunch"). Decoding
 * also buys a real feature: editing the prefix on the phone ("13:00 · X" → "14:00 · X")
 * changes the time in the dashboard, so the time is genuinely two-way despite the API
 * having nowhere to put it.
 *
 * <p>The pattern is deliberately strict — exactly the shape this class writes. A looser
 * one would eat the start of legitimate titles like "10:00 - 11:00 sync with Rishitha".
 */
public final class GoogleTaskTitle {

    /** U+00B7 middle dot, matching the separator Google's own UI uses. */
    static final String SEPARATOR = " · ";

    private static final Pattern PREFIXED =
            Pattern.compile("^(\\d{1,2}):(\\d{2})\\s*·\\s*(.*)$", Pattern.DOTALL);

    private GoogleTaskTitle() {}

    /** A decoded title: the time that was carried in the prefix (nullable) and the rest. */
    public record Decoded(String time, String title) {}

    /**
     * Prepend {@code HH:mm · } when the task has a time of day.
     * A blank or unparseable time is left off rather than guessed at.
     */
    public static String encode(String time, String title) {
        String base = (title == null || title.isBlank()) ? "Untitled" : title.trim();
        String normalized = normalizeTime(time);
        return normalized == null ? base : normalized + SEPARATOR + base;
    }

    /**
     * Split a {@code HH:mm · rest} title. Returns a null time and the title unchanged
     * when there is no prefix, so a task typed on the phone is never mangled.
     */
    public static Decoded decode(String rawTitle) {
        if (rawTitle == null) {
            return new Decoded(null, null);
        }
        Matcher m = PREFIXED.matcher(rawTitle.trim());
        if (!m.matches()) {
            return new Decoded(null, rawTitle.trim());
        }
        int hour = Integer.parseInt(m.group(1));
        int minute = Integer.parseInt(m.group(2));
        String rest = m.group(3).trim();
        // "25:00 · x" or a prefix with nothing after it is a real title, not our encoding.
        if (hour > 23 || minute > 59 || rest.isEmpty()) {
            return new Decoded(null, rawTitle.trim());
        }
        return new Decoded(String.format("%02d:%02d", hour, minute), rest);
    }

    /** Accepts "9:05" and "09:05"; returns canonical HH:mm, or null when there is no usable time. */
    static String normalizeTime(String time) {
        if (time == null || time.isBlank()) {
            return null;
        }
        Matcher m = Pattern.compile("^(\\d{1,2}):(\\d{2})").matcher(time.trim());
        if (!m.find()) {
            return null;
        }
        int hour = Integer.parseInt(m.group(1));
        int minute = Integer.parseInt(m.group(2));
        if (hour > 23 || minute > 59) {
            return null;
        }
        return String.format("%02d:%02d", hour, minute);
    }
}
