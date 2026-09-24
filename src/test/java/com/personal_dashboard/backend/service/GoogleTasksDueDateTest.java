package com.personal_dashboard.backend.service;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The due-date round trip is the one place a timezone bug would be invisible and
 * permanent: every task would quietly land a day early for anyone east of UTC.
 */
class GoogleTasksDueDateTest {

    @Test
    void readsTheDateOffTheUtcInstant() {
        assertEquals(LocalDate.of(2026, 9, 24),
                GoogleTasksSyncService.parseDueDate("2026-09-24T00:00:00.000Z"));
    }

    @Test
    void midnightUtcIsNotShiftedByTheServerZone() {
        // Asia/Kolkata is UTC+5:30, so interpreting this instant locally would give
        // 2026-09-24 05:30 — same date here, but the reverse case below is the trap.
        assertEquals(LocalDate.of(2026, 9, 24),
                GoogleTasksSyncService.parseDueDate("2026-09-24T00:00:00Z"));
    }

    @Test
    void anOffsetTimestampIsNormalisedToUtcBeforeTakingTheDate() {
        // 2026-09-24T05:30+05:30 is 2026-09-24T00:00Z — the date is the UTC one.
        assertEquals(LocalDate.of(2026, 9, 24),
                GoogleTasksSyncService.parseDueDate("2026-09-24T05:30:00+05:30"));
    }

    @Test
    void fallsBackToTheLeadingDateWhenTheTimestampIsOdd() {
        assertEquals(LocalDate.of(2026, 9, 24), GoogleTasksSyncService.parseDueDate("2026-09-24"));
    }

    @Test
    void unparseableDueReturnsNullRatherThanThrowing() {
        // A malformed date must not take down a whole poll.
        assertNull(GoogleTasksSyncService.parseDueDate("not-a-date"));
    }
}
