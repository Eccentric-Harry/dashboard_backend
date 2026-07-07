package com.personal_dashboard.backend.service;

import com.personal_dashboard.backend.model.DailyTask;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Outbound recurrence: local recurrenceFrequency -> Google RRULE/EXDATE. */
class RecurrenceRuleTest {

    @Test
    void dailyTimed_noUntil() {
        DailyTask t = DailyTask.builder()
                .recurrenceFrequency("DAILY").allDay(false)
                .date(LocalDate.of(2026, 7, 8)).startTime("21:00").build();
        assertEquals("RRULE:FREQ=DAILY", GoogleCalendarClient.buildRrule(t, "Asia/Kolkata"));
    }

    @Test
    void weeklyTimed_withUntil_isUtcDateTime() {
        DailyTask t = DailyTask.builder()
                .recurrenceFrequency("WEEKLY").allDay(false)
                .date(LocalDate.of(2026, 7, 8)).startTime("21:00")
                .recurrenceUntil(LocalDate.of(2026, 8, 31)).build();
        // 2026-08-31 23:59:59 Asia/Kolkata (+05:30) -> 18:29:59Z same day.
        assertEquals("RRULE:FREQ=WEEKLY;UNTIL=20260831T182959Z",
                GoogleCalendarClient.buildRrule(t, "Asia/Kolkata"));
    }

    @Test
    void allDayMonthly_withUntil_isDateValue() {
        DailyTask t = DailyTask.builder()
                .recurrenceFrequency("MONTHLY").allDay(true)
                .date(LocalDate.of(2026, 7, 8))
                .recurrenceUntil(LocalDate.of(2026, 12, 31)).build();
        assertEquals("RRULE:FREQ=MONTHLY;UNTIL=20261231",
                GoogleCalendarClient.buildRrule(t, "Asia/Kolkata"));
    }

    @Test
    void exdate_timed_usesTzidAndStartTime() {
        DailyTask t = DailyTask.builder()
                .recurrenceFrequency("DAILY").allDay(false)
                .date(LocalDate.of(2026, 7, 8)).startTime("21:00")
                .excludedDates(List.of(LocalDate.of(2026, 7, 10), LocalDate.of(2026, 7, 12))).build();
        assertEquals("EXDATE;TZID=Asia/Kolkata:20260710T210000,20260712T210000",
                GoogleCalendarClient.buildExdate(t, "Asia/Kolkata"));
    }

    @Test
    void exdate_allDay_usesDateValue() {
        DailyTask t = DailyTask.builder()
                .recurrenceFrequency("DAILY").allDay(true)
                .date(LocalDate.of(2026, 7, 8))
                .excludedDates(List.of(LocalDate.of(2026, 7, 10))).build();
        assertEquals("EXDATE;VALUE=DATE:20260710", GoogleCalendarClient.buildExdate(t, "Asia/Kolkata"));
    }

    @Test
    void exdate_null_whenNoExclusions() {
        DailyTask t = DailyTask.builder().recurrenceFrequency("DAILY").allDay(false)
                .date(LocalDate.of(2026, 7, 8)).startTime("21:00").build();
        assertNull(GoogleCalendarClient.buildExdate(t, "Asia/Kolkata"));
    }
}
