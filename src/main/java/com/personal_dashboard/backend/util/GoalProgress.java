package com.personal_dashboard.backend.util;

import com.personal_dashboard.backend.dto.GoalBoardResponse.DayCell;
import com.personal_dashboard.backend.dto.GoalBoardResponse.DayView;
import com.personal_dashboard.backend.dto.GoalBoardResponse.GoalProgressView;
import com.personal_dashboard.backend.dto.GoalBoardResponse.WeekResult;
import com.personal_dashboard.backend.dto.GoalBoardResponse.WeekView;
import com.personal_dashboard.backend.model.Goal;
import com.personal_dashboard.backend.model.GoalCheckIn;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The one place a goal is judged. Pure: a goal, its check-ins and "today" in, the board's
 * view of it out — no clock, no repository — so the rules are testable on their own and
 * guest mode can port them line for line (mocks/guest-goals.ts).
 *
 * <p>The rules are deliberately forgiving (design/GOALS_BUDDY_PLAN.md):
 * <ul>
 *   <li>Weeks run Monday–Sunday. A DAY goal keeps its week on {@code daysPerWeek} hit
 *       days, so one hectic day never fails a week.</li>
 *   <li>The week a goal starts in is prorated to the days it actually had.</li>
 *   <li>The current week can only add to the streak and the lifetime count — it is never
 *       judged as missed while it is still running.</li>
 * </ul>
 */
public final class GoalProgress {

    public static final String KEPT = "KEPT";
    public static final String ON_PACE = "ON_PACE";
    public static final String TIGHT = "TIGHT";
    public static final String BEHIND = "BEHIND";
    public static final String OUT_OF_REACH = "OUT_OF_REACH";

    /** Completed weeks the board shows behind the current one. */
    public static final int HISTORY_WEEKS = 8;

    /** Days of check-ins the board hands back for undoing — today and the six before it. */
    public static final int RECENT_DAYS = 7;

    private static final double EPSILON = 1e-9;

    private GoalProgress() {
    }

    public static LocalDate weekStart(LocalDate date) {
        return date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }

    public static GoalProgressView evaluate(Goal goal, List<GoalCheckIn> checkIns, LocalDate today) {
        Map<LocalDate, Double> sums = new HashMap<>();
        for (GoalCheckIn c : checkIns) {
            if (c.getDate() != null) {
                sums.merge(c.getDate(), c.getValue(), Double::sum);
            }
        }
        LocalDate start = startOf(goal, today);
        LocalDate thisWeek = weekStart(today);

        // Every week from the start week up to last week, judged in full.
        List<WeekResult> past = new ArrayList<>();
        for (LocalDate ws = weekStart(start); ws.isBefore(thisWeek); ws = ws.plusWeeks(1)) {
            double target = effectiveTarget(goal, ws, start);
            double value = weekValue(goal, sums, ws, start, ws.plusDays(6));
            past.add(WeekResult.builder()
                    .weekStart(ws.toString())
                    .value(value)
                    .target(target)
                    .kept(isKept(value, target))
                    .build());
        }

        WeekView week = currentWeek(goal, sums, today, start);

        int streak = 0;
        for (int i = past.size() - 1; i >= 0 && past.get(i).isKept(); i--) {
            streak++;
        }
        int weeksKept = (int) past.stream().filter(WeekResult::isKept).count();
        if (week.isKept()) {
            streak++;
            weeksKept++;
        }

        LocalDate recentFrom = today.minusDays(RECENT_DAYS - 1);
        List<GoalCheckIn> recent = checkIns.stream()
                .filter(c -> c.getDate() != null && !c.getDate().isBefore(recentFrom) && !c.getDate().isAfter(today))
                .sorted(Comparator.comparing(GoalCheckIn::getDate)
                        .thenComparing(GoalCheckIn::getCreatedAt, Comparator.nullsFirst(Comparator.naturalOrder())))
                .toList();
        double todayValue = sums.getOrDefault(today, 0.0);

        return GoalProgressView.builder()
                .goal(goal)
                .today(DayView.builder()
                        .date(today.toString())
                        .value(todayValue)
                        .target(goal.isDayPeriod() ? goal.getTarget() : null)
                        .hit(isDayHit(goal, todayValue))
                        .build())
                .week(week)
                .history(past.subList(Math.max(0, past.size() - HISTORY_WEEKS), past.size()))
                .recentEntries(recent)
                .weeksKept(weeksKept)
                .weekStreak(streak)
                .build();
    }

    /**
     * Every week this goal has kept, as week starts — completed weeks judged in full, plus
     * the current week once it is kept. The same verdicts {@link #evaluate} counts into
     * {@code weeksKept}; the season map draws them week by week.
     */
    public static Set<LocalDate> keptWeeks(Goal goal, List<GoalCheckIn> checkIns, LocalDate today) {
        Map<LocalDate, Double> sums = new HashMap<>();
        for (GoalCheckIn c : checkIns) {
            if (c.getDate() != null) {
                sums.merge(c.getDate(), c.getValue(), Double::sum);
            }
        }
        LocalDate start = startOf(goal, today);
        LocalDate thisWeek = weekStart(today);
        Set<LocalDate> kept = new HashSet<>();
        for (LocalDate ws = weekStart(start); !ws.isAfter(thisWeek); ws = ws.plusWeeks(1)) {
            LocalDate until = ws.equals(thisWeek) ? today : ws.plusDays(6);
            if (isKept(weekValue(goal, sums, ws, start, until), effectiveTarget(goal, ws, start))) {
                kept.add(ws);
            }
        }
        return kept;
    }

    private static WeekView currentWeek(Goal goal, Map<LocalDate, Double> sums, LocalDate today, LocalDate start) {
        LocalDate ws = weekStart(today);
        LocalDate we = ws.plusDays(6);
        double target = effectiveTarget(goal, ws, start);
        double value = weekValue(goal, sums, ws, start, today);
        boolean kept = isKept(value, target);
        boolean todayHit = isDayHit(goal, sums.getOrDefault(today, 0.0));

        List<DayCell> days = new ArrayList<>(7);
        for (LocalDate d = ws; !d.isAfter(we); d = d.plusDays(1)) {
            double v = sums.getOrDefault(d, 0.0);
            days.add(DayCell.builder()
                    .date(d.toString())
                    .value(v)
                    .hit(isDayHit(goal, v))
                    .today(d.equals(today))
                    .future(d.isAfter(today))
                    .beforeStart(d.isBefore(start))
                    .build());
        }

        // Days still open: today (unless it already counts) through Sunday, never before the start.
        LocalDate firstOpen = later(todayHit && countsDays(goal) ? today.plusDays(1) : today, start);
        int daysLeft = firstOpen.isAfter(we) ? 0 : (int) ChronoUnit.DAYS.between(firstOpen, we) + 1;

        String pace;
        Double perDay = null;
        if (kept) {
            pace = KEPT;
        } else if (countsDays(goal)) {
            int need = (int) Math.ceil(target - value - EPSILON);
            pace = need > daysLeft ? OUT_OF_REACH : need == daysLeft ? TIGHT : ON_PACE;
        } else {
            // COUNT + WEEK: judged against an even share through yesterday, so a morning
            // with nothing logged yet is never "behind" before the day has had its chance.
            LocalDate from = later(ws, start);
            long available = ChronoUnit.DAYS.between(from, we) + 1;
            long elapsed = Math.max(0, ChronoUnit.DAYS.between(from, today));
            double expected = target * elapsed / available;
            pace = value + EPSILON >= expected ? ON_PACE : BEHIND;
            int openDays = Math.max(1, daysLeft);
            perDay = (target - value) / openDays;
        }

        return WeekView.builder()
                .weekStart(ws.toString())
                .value(value)
                .target(target)
                .kept(kept)
                .pace(pace)
                .daysLeft(daysLeft)
                .perDayToFinish(perDay)
                .days(days)
                .build();
    }

    /** Day-counted goals keep a week by how many days were hit, not by how much was logged. */
    static boolean countsDays(Goal goal) {
        return goal.isDayPeriod() || !goal.isCount();
    }

    static boolean isDayHit(Goal goal, double value) {
        if (goal.isDayPeriod()) {
            return value + EPSILON >= goal.getTarget();
        }
        return value > EPSILON;
    }

    private static boolean isKept(double value, double target) {
        return target > EPSILON && value + EPSILON >= target;
    }

    /** The week's value over [max(ws, start), until]. */
    private static double weekValue(Goal goal, Map<LocalDate, Double> sums, LocalDate ws, LocalDate start, LocalDate until) {
        double total = 0;
        for (LocalDate d = later(ws, start); !d.isAfter(until) && !d.isAfter(ws.plusDays(6)); d = d.plusDays(1)) {
            double v = sums.getOrDefault(d, 0.0);
            if (countsDays(goal)) {
                total += isDayHit(goal, v) ? 1 : 0;
            } else {
                total += v;
            }
        }
        return total;
    }

    /**
     * What keeps week {@code ws}. A full week gets the goal's own rule; the week the goal
     * started in gets the rule's fair share of the days it actually had — 5-of-7 started on
     * a Thursday asks for 3 of the 4 days left, not all of them — so a brand-new goal never
     * opens on "every day left".
     */
    static double effectiveTarget(Goal goal, LocalDate ws, LocalDate start) {
        LocalDate from = later(ws, start);
        long available = Math.max(0, ChronoUnit.DAYS.between(from, ws.plusDays(6)) + 1);
        double base = goal.isDayPeriod()
                ? (goal.getDaysPerWeek() == null ? 7 : goal.getDaysPerWeek())
                : goal.getTarget();
        if (available >= 7) {
            return base;
        }
        if (countsDays(goal)) {
            return Math.min(available, Math.max(1, Math.round(base * available / 7.0)));
        }
        return base * available / 7.0;
    }

    static LocalDate startOf(Goal goal, LocalDate today) {
        LocalDate start = goal.getStartDate() == null ? today : goal.getStartDate();
        return start.isAfter(today) ? today : start;
    }

    private static LocalDate later(LocalDate a, LocalDate b) {
        return a.isAfter(b) ? a : b;
    }
}
