package com.personal_dashboard.backend.util;

import com.personal_dashboard.backend.model.DailyTask;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Expands a {@link DailyTask} into the dates it actually occurs on within a window.
 * Occurrences of recurring items are never persisted, so this is the one place that
 * decides what "happens on" means: both the calendar read path and the notification
 * planner go through it, which is what keeps a skipped occurrence skipped in both.
 */
public final class OccurrenceDates {

    /** Recurrence walks are bounded; a corrupt rule must not spin a scheduler thread. */
    private static final int MAX_STEPS = 5000;

    private OccurrenceDates() {
    }

    /**
     * Dates in {@code [from, to]} (both inclusive) on which {@code item} occurs, with
     * explicitly skipped dates removed. Soft-delete and completion are <em>not</em>
     * considered here — callers apply those, because "does it occur" and "should it
     * still be acted on" are different questions.
     */
    public static List<LocalDate> between(DailyTask item, LocalDate from, LocalDate to) {
        if (item == null || item.getDate() == null || from == null || to == null || to.isBefore(from)) {
            return List.of();
        }

        String recurrence = item.getRecurrenceFrequency() == null
                ? "NONE"
                : item.getRecurrenceFrequency().toUpperCase(Locale.ROOT);

        if ("NONE".equals(recurrence)) {
            LocalDate date = item.getDate();
            boolean inWindow = !date.isBefore(from) && !date.isAfter(to);
            return inWindow && !isExcluded(item, date) ? List.of(date) : List.of();
        }

        LocalDate end = item.getRecurrenceUntil() != null && item.getRecurrenceUntil().isBefore(to)
                ? item.getRecurrenceUntil()
                : to;
        if (end.isBefore(from)) {
            return List.of();
        }

        List<LocalDate> dates = new ArrayList<>();
        LocalDate cursor = item.getDate();
        int guard = 0;
        while (cursor.isBefore(from) && guard++ < MAX_STEPS) {
            cursor = next(cursor, recurrence);
        }
        while (!cursor.isAfter(end) && guard++ < MAX_STEPS) {
            if (!cursor.isBefore(from) && !isExcluded(item, cursor)) {
                dates.add(cursor);
            }
            cursor = next(cursor, recurrence);
        }
        return dates;
    }

    /** True when this specific occurrence has been completed (per-date for recurring items). */
    public static boolean isCompletedOn(DailyTask item, LocalDate date) {
        if (item.isRecurring()) {
            return item.getCompletedDates() != null && item.getCompletedDates().contains(date);
        }
        return Boolean.TRUE.equals(item.getCompleted());
    }

    /** True when this specific occurrence has been cancelled (per-date for recurring items). */
    public static boolean isCancelledOn(DailyTask item, LocalDate date) {
        if (item.isRecurring()) {
            return item.getCancelledDates() != null && item.getCancelledDates().contains(date);
        }
        return Boolean.TRUE.equals(item.getCancelled());
    }

    private static boolean isExcluded(DailyTask item, LocalDate date) {
        return item.getExcludedDates() != null && item.getExcludedDates().contains(date);
    }

    private static LocalDate next(LocalDate current, String recurrence) {
        return switch (recurrence) {
            case "DAILY" -> current.plusDays(1);
            case "WEEKLY" -> current.plusWeeks(1);
            case "MONTHLY" -> current.plusMonths(1);
            // Unknown frequency: step far enough that the walk terminates immediately.
            default -> current.plusYears(100);
        };
    }
}
