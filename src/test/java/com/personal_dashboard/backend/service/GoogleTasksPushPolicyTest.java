package com.personal_dashboard.backend.service;

import com.personal_dashboard.backend.model.DailyTask;
import com.personal_dashboard.backend.model.EventOrigin;
import com.personal_dashboard.backend.model.GoogleSyncStore;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

class GoogleTasksPushPolicyTest {

    private static final int RETENTION = 30;

    private GoogleSyncStore store(String email) {
        return GoogleSyncStore.builder().email(email).tasksSyncEnabled(true).status("CONNECTED").build();
    }

    private DailyTask task(String itemType) {
        return DailyTask.builder().id("T1").itemType(itemType).completed(false).origin(EventOrigin.local()).build();
    }

    // ── The gate that keeps calendar entries out of the to-do list ──────────────

    @Test
    void calendarEvents_areNeverMirroredToGoogleTasks() {
        assertFalse(GoogleTasksPushPolicy.shouldPush(task("EVENT"), store("a@x.com"), RETENTION),
                "A Google Calendar entry is not a to-do — this is what kept 669 imported "
                        + "events out of the user's phone widget.");
    }

    @Test
    void plannerTasks_andLegacyNullItemType_areMirrored() {
        assertTrue(GoogleTasksPushPolicy.shouldPush(task("TASK"), store("a@x.com"), RETENTION));
        assertTrue(GoogleTasksPushPolicy.shouldPush(task(null), store("a@x.com"), RETENTION),
                "Rows predating itemType are planner tasks by default.");
    }

    @Test
    void itemTypeMatchIsCaseInsensitive() {
        assertTrue(GoogleTasksPushPolicy.isPlannerTask(task("task")));
        assertFalse(GoogleTasksPushPolicy.isPlannerTask(task("event")));
    }

    // ── Opt-in and connection state ────────────────────────────────────────────

    @Test
    void disabledAccount_isSkipped() {
        GoogleSyncStore off = GoogleSyncStore.builder().email("a@x.com").tasksSyncEnabled(false).build();
        assertFalse(GoogleTasksPushPolicy.shouldPush(task("TASK"), off, RETENTION));
    }

    @Test
    void disconnectedAccount_isSkipped() {
        GoogleSyncStore dead = GoogleSyncStore.builder()
                .email("a@x.com").tasksSyncEnabled(true).status("DISCONNECTED").build();
        assertFalse(GoogleTasksPushPolicy.shouldPush(task("TASK"), dead, RETENTION));
    }

    // ── Loop prevention ────────────────────────────────────────────────────────

    @Test
    void taskPulledFromAnAccount_isNeverPushedBackToIt() {
        DailyTask imported = task("TASK");
        imported.setOrigin(EventOrigin.googleTasks("a@x.com", "list-1"));
        assertFalse(GoogleTasksPushPolicy.shouldPush(imported, store("a@x.com"), RETENTION));
    }

    @Test
    void taskPulledFromOneAccount_stillMirrorsToAnother() {
        DailyTask imported = task("TASK");
        imported.setOrigin(EventOrigin.googleTasks("a@x.com", "list-1"));
        assertTrue(GoogleTasksPushPolicy.shouldPush(imported, store("b@x.com"), RETENTION));
    }

    // ── Retention window ───────────────────────────────────────────────────────

    @Test
    void openTasks_areAlwaysInScope_howeverOld() {
        DailyTask old = task("TASK");
        old.setCompleted(false);
        assertTrue(GoogleTasksPushPolicy.isWithinWindow(old, RETENTION),
                "An overdue to-do is exactly what a widget needs to show.");
    }

    @Test
    void recentlyCompletedTasks_stayInScope() {
        DailyTask done = task("TASK");
        done.setCompleted(true);
        done.setCompletedAt(LocalDateTime.now().minusDays(3));
        assertTrue(GoogleTasksPushPolicy.isWithinWindow(done, RETENTION));
    }

    @Test
    void longCompletedTasks_fallOutOfScope() {
        DailyTask ancient = task("TASK");
        ancient.setCompleted(true);
        ancient.setCompletedAt(LocalDateTime.now().minusDays(RETENTION + 1));
        assertFalse(GoogleTasksPushPolicy.isWithinWindow(ancient, RETENTION));
    }

    @Test
    void completedWithUnknownTimestamp_treatedAsOld() {
        DailyTask done = task("TASK");
        done.setCompleted(true);
        done.setCompletedAt(null);
        assertFalse(GoogleTasksPushPolicy.isWithinWindow(done, RETENTION),
                "Better to omit one task than to flood Google with undated history.");
    }

    @Test
    void zeroRetention_mirrorsOpenTasksOnly() {
        DailyTask done = task("TASK");
        done.setCompleted(true);
        done.setCompletedAt(LocalDateTime.now());
        assertFalse(GoogleTasksPushPolicy.isWithinWindow(done, 0));
        assertTrue(GoogleTasksPushPolicy.isWithinWindow(task("TASK"), 0));
    }
}
