package com.personal_dashboard.backend.util;

import com.personal_dashboard.backend.dto.GoalJourneyResponse.JourneyDay;
import com.personal_dashboard.backend.model.GoalCheckIn;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GoalJourneyTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 1);

    private static GoalCheckIn at(LocalDate date, double value, String practice, String note, int minute) {
        return GoalCheckIn.builder().goalId("g").date(date).value(value).practice(practice).note(note)
                .createdAt(Instant.parse("2026-10-01T08:00:00Z").plusSeconds(minute * 60L)).build();
    }

    @Test
    void oneDayPerDayTendedOldestFirstWithNoGaps() {
        List<JourneyDay> days = GoalJourney.days(List.of(
                at(TODAY, 1, "walk", null, 30),
                at(TODAY.minusDays(5), 1, "breathe", "slow exhale helped", 0),
                at(TODAY, 1, "breathe", "after work", 10),
                at(TODAY, 1, "walk", null, 40),
                at(TODAY.plusDays(1), 1, "write", null, 0)), TODAY);

        assertEquals(2, days.size(), "quiet days and future days are simply absent");
        assertEquals(TODAY.minusDays(5).toString(), days.get(0).getDate());
        JourneyDay today = days.get(1);
        assertEquals(3, today.getEntries());
        assertEquals(3, today.getValue());
        assertEquals(List.of("breathe", "walk"), today.getPractices(), "first-logged order, no repeats");
        assertEquals(List.of("after work"), today.getNotes());
    }

    @Test
    void sessionsAndMinutesAreKeptPerDayInOrderDone() {
        GoalCheckIn first = at(TODAY, 1, "breathe", null, 0);
        first.setSession("arrive");
        first.setMinutes(3);
        GoalCheckIn replay = at(TODAY, 1, "still", null, 20);
        replay.setSession("arrive");
        replay.setMinutes(4);
        List<JourneyDay> days = GoalJourney.days(List.of(replay, first, at(TODAY, 1, "walk", null, 30)), TODAY);
        assertEquals(List.of("arrive", "arrive"), days.get(0).getSessions(), "a replay is kept, in order");
        assertEquals(7, days.get(0).getMinutes());
    }

    @Test
    void minutesAddUpForAGoalCountedInMinutes() {
        List<JourneyDay> days = GoalJourney.days(List.of(at(TODAY, 5, "breathe", null, 0), at(TODAY, 10, null, null, 5)), TODAY);
        assertEquals(15, days.get(0).getValue());
        assertEquals(List.of("breathe"), days.get(0).getPractices());
    }
}
