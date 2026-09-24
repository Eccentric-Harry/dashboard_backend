package com.personal_dashboard.backend.service;

import com.personal_dashboard.backend.model.DailyTask;
import com.personal_dashboard.backend.model.EventOrigin;
import com.personal_dashboard.backend.model.GoogleSyncStore;

import java.time.LocalDateTime;

/**
 * Decides which local rows belong in Google Tasks, and for which account.
 *
 * <p>Three independent gates:
 * <ol>
 *   <li><b>It must actually be a task.</b> {@code itemType} EVENT never goes to Google
 *       Tasks — calendar entries belong in Calendar. This is the gate that keeps the
 *       hundreds of imported calendar events (lunches, flights, birthdays) out of the
 *       user's phone widget.</li>
 *   <li><b>Loop prevention.</b> A task pulled <em>from</em> Google Tasks is never pushed
 *       back to the account it came from.</li>
 *   <li><b>Volume.</b> Completed tasks older than the retention window are not mirrored.
 *       Without this the first sync would push years of finished work into a to-do list
 *       whose whole point is showing what is left.</li>
 * </ol>
 */
public final class GoogleTasksPushPolicy {

    private GoogleTasksPushPolicy() {}

    /** True when this row is a planner task rather than a calendar event. */
    public static boolean isPlannerTask(DailyTask task) {
        String itemType = task.getItemType();
        return itemType == null || "TASK".equalsIgnoreCase(itemType);
    }

    /**
     * @param completedRetentionDays how far back completed tasks are still mirrored;
     *                               a non-positive value mirrors open tasks only.
     */
    public static boolean shouldPush(DailyTask task, GoogleSyncStore store, int completedRetentionDays) {
        if (!store.isTasksSyncEnabled() || store.isDisconnected()) {
            return false;
        }
        if (!isPlannerTask(task)) {
            return false;
        }
        EventOrigin origin = task.getOrigin();
        if (origin != null && origin.isGoogleTasks()
                && store.getEmail() != null && store.getEmail().equalsIgnoreCase(origin.getAccountId())) {
            return false; // came from this account — pushing it back would echo
        }
        return isWithinWindow(task, completedRetentionDays);
    }

    /**
     * Completed tasks fall out of scope once they age past the retention window.
     * An open task is always in scope, however old — an overdue to-do is the thing a
     * widget most needs to show.
     */
    public static boolean isWithinWindow(DailyTask task, int completedRetentionDays) {
        if (!Boolean.TRUE.equals(task.getCompleted())) {
            return true;
        }
        if (completedRetentionDays <= 0) {
            return false;
        }
        LocalDateTime completedAt = task.getCompletedAt();
        if (completedAt == null) {
            // Completed at an unknown time — treat as old rather than flooding Google.
            return false;
        }
        return completedAt.isAfter(LocalDateTime.now().minusDays(completedRetentionDays));
    }
}
