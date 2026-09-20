package com.personal_dashboard.backend.util;

import com.personal_dashboard.backend.model.DailyTask;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The single definition of "does this item occur on this date", shared by the calendar and the planner. */
class OccurrenceDatesTest {

    private static final LocalDate MON = LocalDate.of(2026, 9, 21);

    @Test
    void nonRecurring_onlyItsOwnDate() {
        DailyTask t = DailyTask.builder().date(MON).recurrenceFrequency("NONE").build();

        assertEquals(List.of(MON), OccurrenceDates.between(t, MON.minusDays(3), MON.plusDays(3)));
        assertTrue(OccurrenceDates.between(t, MON.plusDays(1), MON.plusDays(5)).isEmpty());
    }

    @Test
    void daily_expandsAcrossWindowInclusive() {
        DailyTask t = DailyTask.builder().date(MON).recurrenceFrequency("DAILY").build();

        List<LocalDate> dates = OccurrenceDates.between(t, MON, MON.plusDays(2));

        assertEquals(List.of(MON, MON.plusDays(1), MON.plusDays(2)), dates);
    }

    @Test
    void weekly_stepsSevenDays() {
        DailyTask t = DailyTask.builder().date(MON).recurrenceFrequency("WEEKLY").build();

        assertEquals(List.of(MON.plusWeeks(1)), OccurrenceDates.between(t, MON.plusDays(1), MON.plusDays(9)));
    }

    @Test
    void recurrenceUntil_stopsTheSeries() {
        DailyTask t = DailyTask.builder()
                .date(MON).recurrenceFrequency("DAILY").recurrenceUntil(MON.plusDays(1)).build();

        assertEquals(List.of(MON, MON.plusDays(1)), OccurrenceDates.between(t, MON, MON.plusDays(5)));
    }

    @Test
    void excludedDates_areSkipped() {
        DailyTask t = DailyTask.builder()
                .date(MON).recurrenceFrequency("DAILY")
                .excludedDates(List.of(MON.plusDays(1)))
                .build();

        assertEquals(List.of(MON, MON.plusDays(2)), OccurrenceDates.between(t, MON, MON.plusDays(2)));
    }

    @Test
    void completionIsPerOccurrenceForRecurringItems() {
        DailyTask recurring = DailyTask.builder()
                .date(MON).recurrenceFrequency("DAILY")
                .completed(false)
                .completedDates(List.of(MON))
                .build();

        assertTrue(OccurrenceDates.isCompletedOn(recurring, MON));
        assertFalse(OccurrenceDates.isCompletedOn(recurring, MON.plusDays(1)));
    }

    @Test
    void completionIsTheFlagForOneOffItems() {
        DailyTask oneOff = DailyTask.builder().date(MON).recurrenceFrequency("NONE").completed(true).build();

        assertTrue(OccurrenceDates.isCompletedOn(oneOff, MON));
    }

    @Test
    void cancellationIsPerOccurrenceForRecurringItems() {
        DailyTask recurring = DailyTask.builder()
                .date(MON).recurrenceFrequency("WEEKLY")
                .cancelledDates(List.of(MON.plusWeeks(1)))
                .build();

        assertFalse(OccurrenceDates.isCancelledOn(recurring, MON));
        assertTrue(OccurrenceDates.isCancelledOn(recurring, MON.plusWeeks(1)));
    }

    @Test
    void missingDate_yieldsNothingRatherThanThrowing() {
        assertTrue(OccurrenceDates.between(DailyTask.builder().build(), MON, MON.plusDays(1)).isEmpty());
        assertTrue(OccurrenceDates.between(null, MON, MON.plusDays(1)).isEmpty());
    }

    @Test
    void unknownFrequency_doesNotSpin() {
        DailyTask t = DailyTask.builder().date(MON).recurrenceFrequency("FORTNIGHTLY").build();

        assertEquals(List.of(MON), OccurrenceDates.between(t, MON, MON.plusDays(30)));
    }
}
