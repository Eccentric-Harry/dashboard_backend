package com.personal_dashboard.backend.dto;

import com.personal_dashboard.backend.model.Goal;
import com.personal_dashboard.backend.model.GoalCheckIn;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Everything the /goals board renders, derived on read by GoalProgress. Mirrored by
 * GoalBoard / GoalProgressView in types/goals.ts; guest mode ports the same rules
 * (mocks/guest-goals.ts), so a change here is a change there.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GoalBoardResponse {

    /** The day the board was judged for (the client's local today). */
    private String date;

    /** Monday of {@link #date}'s week. */
    private String weekStart;

    /** Active goals in board order. */
    private List<GoalProgressView> goals;

    /** The camp around the board: sparks, chest, quests, season map, the buddy's look. */
    private CampView camp;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class GoalProgressView {
        private Goal goal;
        private DayView today;
        private WeekView week;
        /** Completed weeks before this one, oldest first — at most 8, never before the start week. */
        private List<WeekResult> history;
        /** Check-ins from the last 7 days (today included), oldest first — what the log modal can undo. */
        private List<GoalCheckIn> recentEntries;
        /** Every kept week since the goal started, this one included once kept. Only grows. */
        private int weeksKept;
        /** Consecutive kept weeks ending last week, plus this week once it is kept. */
        private int weekStreak;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DayView {
        private String date;
        private double value;
        /** The day's target for DAY goals; null for WEEK goals, which have none. */
        private Double target;
        /** DAY: value reached the target. WEEK: anything was logged today. */
        private boolean hit;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class WeekView {
        private String weekStart;
        /** Hit days for day-counted goals; the week's sum for COUNT + WEEK. */
        private double value;
        /** What keeps the week — prorated in the week the goal started. */
        private double target;
        private boolean kept;
        /** KEPT | ON_PACE | TIGHT | BEHIND | OUT_OF_REACH */
        private String pace;
        /** Days still open to log in this week, today included unless already hit. */
        private int daysLeft;
        /** COUNT + WEEK only: an even share of what's left over the open days; null otherwise or once kept. */
        private Double perDayToFinish;
        /** Monday → Sunday. */
        private List<DayCell> days;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DayCell {
        private String date;
        private double value;
        private boolean hit;
        private boolean today;
        private boolean future;
        /** Before the goal existed — shown, never judged. */
        private boolean beforeStart;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class WeekResult {
        private String weekStart;
        private double value;
        private double target;
        private boolean kept;
    }
}
