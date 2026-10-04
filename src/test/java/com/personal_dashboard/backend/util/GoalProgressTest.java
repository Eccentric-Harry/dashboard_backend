package com.personal_dashboard.backend.util;

import com.personal_dashboard.backend.dto.GoalBoardResponse.GoalProgressView;
import com.personal_dashboard.backend.model.Goal;
import com.personal_dashboard.backend.model.GoalCheckIn;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GoalProgressTest {

    // Monday 28 Sep 2026 → Sunday 4 Oct 2026.
    private static final LocalDate MON = LocalDate.of(2026, 9, 28);
    private static final LocalDate THU = MON.plusDays(3);

    private static Goal dailyAmount(double perDay, int daysPerWeek, LocalDate start) {
        return Goal.builder().id("g").userId("u").measure(Goal.MEASURE_COUNT).period(Goal.PERIOD_DAY)
                .target(perDay).unit("pages").daysPerWeek(daysPerWeek).startDate(start).status(Goal.STATUS_ACTIVE).build();
    }

    private static Goal timesPerWeek(int times, LocalDate start) {
        return Goal.builder().id("g").userId("u").measure(Goal.MEASURE_CHECK).period(Goal.PERIOD_WEEK)
                .target(times).startDate(start).status(Goal.STATUS_ACTIVE).build();
    }

    private static Goal weeklyTotal(double perWeek, LocalDate start) {
        return Goal.builder().id("g").userId("u").measure(Goal.MEASURE_COUNT).period(Goal.PERIOD_WEEK)
                .target(perWeek).unit("min").startDate(start).status(Goal.STATUS_ACTIVE).build();
    }

    private static GoalCheckIn at(LocalDate date, double value) {
        return GoalCheckIn.builder().goalId("g").userId("u").date(date).value(value).build();
    }

    @Test
    void sittingsOnOneDayAddUpToAHit() {
        GoalProgressView v = GoalProgress.evaluate(dailyAmount(10, 5, MON.minusWeeks(4)),
                List.of(at(THU, 4), at(THU, 6)), THU);
        assertEquals(10, v.getToday().getValue());
        assertTrue(v.getToday().isHit());
        assertEquals(10.0, v.getToday().getTarget());
    }

    @Test
    void dailyGoalKeepsTheWeekOnItsHitDaysNotEveryDay() {
        Goal goal = dailyAmount(10, 3, MON.minusWeeks(4));
        GoalProgressView v = GoalProgress.evaluate(goal, List.of(at(MON, 10), at(MON.plusDays(1), 12), at(THU, 10)), THU);
        assertTrue(v.getWeek().isKept());
        assertEquals(GoalProgress.KEPT, v.getWeek().getPace());
        assertEquals(3, v.getWeek().getValue());
        // A short day is logged but is not a hit.
        GoalProgressView partial = GoalProgress.evaluate(goal, List.of(at(MON, 10), at(THU, 4)), THU);
        assertFalse(partial.getWeek().isKept());
        assertEquals(1, partial.getWeek().getValue());
    }

    @Test
    void paceSaysTightWhenEveryOpenDayIsNeededAndOutOfReachBeyondThat() {
        Goal goal = dailyAmount(10, 5, MON.minusWeeks(4));
        // Thursday, one hit so far, today not hit: Thu–Sun = 4 open days, 4 needed.
        GoalProgressView tight = GoalProgress.evaluate(goal, List.of(at(MON, 10)), THU);
        assertEquals(GoalProgress.TIGHT, tight.getWeek().getPace());
        assertEquals(4, tight.getWeek().getDaysLeft());

        GoalProgressView gone = GoalProgress.evaluate(goal, List.of(), THU);
        assertEquals(GoalProgress.OUT_OF_REACH, gone.getWeek().getPace());

        GoalProgressView fine = GoalProgress.evaluate(goal, List.of(at(MON, 10), at(MON.plusDays(1), 10)), THU);
        assertEquals(GoalProgress.ON_PACE, fine.getWeek().getPace());
    }

    @Test
    void aDayAlreadyHitIsNotCountedAsOpen() {
        Goal goal = dailyAmount(10, 5, MON.minusWeeks(4));
        GoalProgressView v = GoalProgress.evaluate(goal, List.of(at(MON, 10), at(THU, 10)), THU);
        // Fri–Sun open, 3 more needed.
        assertEquals(3, v.getWeek().getDaysLeft());
        assertEquals(GoalProgress.TIGHT, v.getWeek().getPace());
    }

    @Test
    void theStartWeekIsProratedToTheDaysItHad() {
        LocalDate friday = MON.plusDays(4);
        Goal goal = dailyAmount(10, 5, friday);
        // Fri, Sat, Sun available → 5/7 of 3 days ≈ 2 hit days keep it, not all three.
        assertEquals(2, GoalProgress.effectiveTarget(goal, MON, friday));
        GoalProgressView v = GoalProgress.evaluate(goal, List.of(at(friday, 10)), friday);
        assertEquals(2, v.getWeek().getTarget());
        assertEquals(GoalProgress.ON_PACE, v.getWeek().getPace());
        // A Thursday start asks for 3 of the 4 days left, so a new goal opens on pace.
        LocalDate thursday = MON.plusDays(3);
        GoalProgressView fresh = GoalProgress.evaluate(dailyAmount(10, 5, thursday), List.of(), thursday);
        assertEquals(3, fresh.getWeek().getTarget());
        assertEquals(GoalProgress.ON_PACE, fresh.getWeek().getPace());
        // Never more than the days there are, never less than one.
        assertEquals(1, GoalProgress.effectiveTarget(timesPerWeek(1, MON.plusDays(6)), MON, MON.plusDays(6)));
        assertTrue(v.getWeek().getDays().get(0).isBeforeStart());
        assertFalse(v.getWeek().getDays().get(4).isBeforeStart());

        Goal weekly = weeklyTotal(420, friday);
        assertEquals(180, GoalProgress.effectiveTarget(weekly, MON, friday), 1e-9);
    }

    @Test
    void weeklyTotalIsNeverBehindBeforeTheDayHasHadItsChance() {
        Goal goal = weeklyTotal(420, MON.minusWeeks(4));
        GoalProgressView monday = GoalProgress.evaluate(goal, List.of(), MON);
        assertEquals(GoalProgress.ON_PACE, monday.getWeek().getPace());
        assertEquals(60, monday.getWeek().getPerDayToFinish(), 1e-9);

        // Thursday: 3 days elapsed → 180 expected; 90 logged is behind.
        GoalProgressView behind = GoalProgress.evaluate(goal, List.of(at(MON, 90)), THU);
        assertEquals(GoalProgress.BEHIND, behind.getWeek().getPace());
        assertEquals(330 / 4.0, behind.getWeek().getPerDayToFinish(), 1e-9);

        GoalProgressView kept = GoalProgress.evaluate(goal, List.of(at(MON, 300), at(THU, 120)), THU);
        assertEquals(GoalProgress.KEPT, kept.getWeek().getPace());
        assertNull(kept.getWeek().getPerDayToFinish());
        // A weekly goal has no per-day target; any logging counts as today's hit.
        assertNull(kept.getToday().getTarget());
        assertTrue(kept.getToday().isHit());
    }

    @Test
    void timesPerWeekCountsDaysNotEntries() {
        Goal goal = timesPerWeek(3, MON.minusWeeks(4));
        GoalProgressView v = GoalProgress.evaluate(goal, List.of(at(MON, 1), at(MON, 1), at(THU, 1)), THU);
        assertEquals(2, v.getWeek().getValue());
        assertFalse(v.getWeek().isKept());
        assertEquals(GoalProgress.ON_PACE, v.getWeek().getPace());
    }

    @Test
    void streakRunsBackFromLastWeekAndTheCurrentWeekOnlyAdds() {
        Goal goal = timesPerWeek(1, MON.minusWeeks(4));
        List<GoalCheckIn> checkIns = new ArrayList<>();
        checkIns.add(at(MON.minusWeeks(4), 1)); // kept
        // week -3 missed
        checkIns.add(at(MON.minusWeeks(2), 1)); // kept
        checkIns.add(at(MON.minusWeeks(1), 1)); // kept

        GoalProgressView running = GoalProgress.evaluate(goal, checkIns, THU);
        assertEquals(2, running.getWeekStreak(), "an unfinished week never breaks the streak");
        assertEquals(3, running.getWeeksKept());
        assertEquals(4, running.getHistory().size());
        assertFalse(running.getHistory().get(1).isKept());

        checkIns.add(at(MON, 1));
        GoalProgressView kept = GoalProgress.evaluate(goal, checkIns, THU);
        assertEquals(3, kept.getWeekStreak());
        assertEquals(4, kept.getWeeksKept());
    }

    @Test
    void historyShowsAtMostEightWeeksButLifetimeCountsThemAll() {
        Goal goal = timesPerWeek(1, MON.minusWeeks(12));
        List<GoalCheckIn> checkIns = new ArrayList<>();
        for (int w = 1; w <= 12; w++) {
            checkIns.add(at(MON.minusWeeks(w), 1));
        }
        GoalProgressView v = GoalProgress.evaluate(goal, checkIns, THU);
        assertEquals(GoalProgress.HISTORY_WEEKS, v.getHistory().size());
        assertEquals(MON.minusWeeks(8).toString(), v.getHistory().get(0).getWeekStart());
        assertEquals(12, v.getWeeksKept());
        assertEquals(12, v.getWeekStreak());
    }

    @Test
    void recentEntriesCoverTheLastSevenDaysAcrossTheWeekBoundary() {
        GoalProgressView v = GoalProgress.evaluate(dailyAmount(10, 5, MON.minusWeeks(2)),
                List.of(at(THU, 2), at(MON, 3), at(THU.minusDays(6), 4), at(THU.minusDays(7), 5)), THU);
        assertEquals(3, v.getRecentEntries().size());
        assertEquals(THU.minusDays(6), v.getRecentEntries().get(0).getDate());
        assertEquals(THU, v.getRecentEntries().get(2).getDate());
        assertTrue(v.getWeek().getDays().get(3).isToday());
        assertTrue(v.getWeek().getDays().get(4).isFuture());
    }
}
