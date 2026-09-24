package com.personal_dashboard.backend.service;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Carries a task's time of day through Google Tasks, which has nowhere to put it.
 *
 * <p><b>The constraint.</b> A Google task has no time field. {@code due} is typed as an
 * RFC 3339 timestamp but the server discards the time portion — verified directly against
 * the live API, which normalises {@code 2026-09-26T13:00:00Z} to {@code 2026-09-26T00:00:00.000Z}.
 * Google's own documentation says it outright: it is not possible to read or write the time
 * a task is scheduled for through the API. The times shown in Google's first-party apps live
 * somewhere third parties cannot reach.
 *
 * <p><b>Where it goes instead.</b> The first line of the notes, as {@code ⏰ HH:mm}, followed
 * by a blank line and then whatever notes the task actually has. The Tasks widget renders
 * notes as a second line under the title, so the time stays visible while the title stays
 * clean. Putting it first means it survives the widget truncating longer notes.
 *
 * <p><b>Legacy titles.</b> An earlier version wrote the time as a {@code HH:mm · } prefix on
 * the title. Those tasks are already in Google, so {@link #stripLegacyTitlePrefix} still reads
 * and removes the prefix on the way in — otherwise the prefix would be pulled into the stored
 * title and stick there permanently. Nothing writes that format any more; the next push moves
 * each task's time into its notes and leaves a clean title behind.
 *
 * <p>Both patterns are deliberately strict, matching only the exact shapes this class writes,
 * so they cannot eat a legitimate title like {@code 10:00 - 11:00 sync with Rishitha} or a
 * note that merely mentions a time.
 */
public final class GoogleTaskTimeEncoding {

    /** U+23F0 alarm clock — distinctive enough that the decoder cannot false-positive on prose. */
    static final String TIME_MARKER = "⏰ ";

    private static final Pattern NOTES_TIME =
            Pattern.compile("^\\s*⏰\\s*(\\d{1,2}):(\\d{2})\\s*(?:\\R([\\s\\S]*))?$");

    /** Legacy: "HH:mm · rest" written into the title by an earlier version. */
    private static final Pattern LEGACY_TITLE =
            Pattern.compile("^(\\d{1,2}):(\\d{2})\\s*·\\s*(.*)$", Pattern.DOTALL);

    private GoogleTaskTimeEncoding() {}

    /** A decoded value: the time that was carried alongside it (nullable), and the rest. */
    public record Decoded(String time, String value) {}

    /**
     * Put {@code ⏰ HH:mm} on the first line, then a blank line, then the real notes.
     * With no time this returns the notes untouched, so an untimed task is never marked up.
     */
    public static String encodeNotes(String time, String notes) {
        String body = notes == null ? "" : notes.strip();
        String normalized = normalizeTime(time);
        if (normalized == null) {
            return body;
        }
        return body.isEmpty() ? TIME_MARKER + normalized
                              : TIME_MARKER + normalized + "\n\n" + body;
    }

    /**
     * Split the {@code ⏰ HH:mm} first line back off. Returns a null time and the notes
     * unchanged when the marker is absent, so notes typed on the phone are never mangled.
     * Editing the marker on the phone reschedules the task, which is what makes the time
     * two-way despite the API having no field for it.
     */
    public static Decoded decodeNotes(String notes) {
        if (notes == null || notes.isBlank()) {
            return new Decoded(null, null);
        }
        Matcher m = NOTES_TIME.matcher(notes.strip());
        if (!m.matches()) {
            return new Decoded(null, notes.strip());
        }
        String time = clamp(m.group(1), m.group(2));
        if (time == null) {
            return new Decoded(null, notes.strip());
        }
        String rest = m.group(3) == null ? "" : m.group(3).strip();
        return new Decoded(time, rest.isEmpty() ? null : rest);
    }

    /**
     * Remove a legacy {@code HH:mm · } title prefix, returning the time it carried.
     * Read-only migration support — nothing writes this format any more.
     */
    public static Decoded stripLegacyTitlePrefix(String title) {
        if (title == null) {
            return new Decoded(null, null);
        }
        Matcher m = LEGACY_TITLE.matcher(title.strip());
        if (!m.matches()) {
            return new Decoded(null, title.strip());
        }
        String time = clamp(m.group(1), m.group(2));
        String rest = m.group(3).strip();
        if (time == null || rest.isEmpty()) {
            return new Decoded(null, title.strip());
        }
        return new Decoded(time, rest);
    }

    /** Accepts "9:05" and "09:05"; returns canonical HH:mm, or null when unusable. */
    static String normalizeTime(String time) {
        if (time == null || time.isBlank()) {
            return null;
        }
        Matcher m = Pattern.compile("^(\\d{1,2}):(\\d{2})").matcher(time.strip());
        return m.find() ? clamp(m.group(1), m.group(2)) : null;
    }

    private static String clamp(String h, String m) {
        int hour = Integer.parseInt(h);
        int minute = Integer.parseInt(m);
        return (hour > 23 || minute > 59) ? null : String.format("%02d:%02d", hour, minute);
    }
}
