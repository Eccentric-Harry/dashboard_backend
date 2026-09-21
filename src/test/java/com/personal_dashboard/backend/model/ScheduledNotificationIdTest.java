package com.personal_dashboard.backend.model;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The id is both a Mongo {@code _id} (so it must be deterministic) and a URL path segment on
 * {@code /notifications/{id}/ack} (so it must be legal in a URI). The first version satisfied
 * only the first requirement: it joined with '|', which Tomcat rejects with a 400 before the
 * request reaches a controller — acknowledging or reading any notification simply failed.
 */
class ScheduledNotificationIdTest {

    private static final LocalDate DAY = LocalDate.of(2026, 9, 21);

    @Test
    void isStableForTheSameOccurrence() {
        String first = ScheduledNotification.deterministicId(
                "user-1", ScheduledNotification.SOURCE_CALENDAR_ITEM, "task-1", DAY,
                ScheduledNotification.KIND_START);
        String second = ScheduledNotification.deterministicId(
                "user-1", ScheduledNotification.SOURCE_CALENDAR_ITEM, "task-1", DAY,
                ScheduledNotification.KIND_START);

        assertEquals(first, second);
    }

    @Test
    void differsPerUser_perSource_perDate_andPerKind() {
        String base = ScheduledNotification.deterministicId("u1", "CALENDAR_ITEM", "t1", DAY, "START");

        assertNotEquals(base, ScheduledNotification.deterministicId("u2", "CALENDAR_ITEM", "t1", DAY, "START"));
        assertNotEquals(base, ScheduledNotification.deterministicId("u1", "CALENDAR_ITEM", "t2", DAY, "START"));
        assertNotEquals(base, ScheduledNotification.deterministicId("u1", "CALENDAR_ITEM", "t1", DAY.plusDays(1), "START"));
        assertNotEquals(base, ScheduledNotification.deterministicId("u1", "CALENDAR_ITEM", "t1", DAY, "ALL_DAY"));
    }

    @Test
    void containsOnlyCharactersThatSurviveAUrlPath() {
        String id = ScheduledNotification.deterministicId(
                "harinadh", "CALENDAR_ITEM", "68cf0a1b2c3d4e5f60718293", DAY, "START");

        assertTrue(id.matches("[A-Za-z0-9._~-]+"), "id must be URI-unreserved, was: " + id);
        assertFalse(id.contains("|"));
        assertFalse(id.contains("/"));
        assertFalse(id.contains(" "));
    }

    @Test
    void sanitisesComponentsThatWouldBreakTheUrl() {
        String id = ScheduledNotification.deterministicId(
                "user|with/bad chars", "CALENDAR_ITEM", "task?id=1", DAY, "START");

        assertTrue(id.matches("[A-Za-z0-9._~-]+"), "id must be URI-unreserved, was: " + id);
    }

    @Test
    void blankComponentsDoNotCollapseTheStructure() {
        String id = ScheduledNotification.deterministicId("u1", "CALENDAR_ITEM", null, DAY, "START");

        assertEquals(5, id.split("\\.").length);
    }
}
