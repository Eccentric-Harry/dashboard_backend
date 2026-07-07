package com.personal_dashboard.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Header + Evidence-Locker summary for the Mind tab: the objective record pulled from
 * data the user already generates elsewhere in Life OS, plus the week's loop-closing counts.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MindSummaryResponse {

    // Auto-evidence over the last 7 days — "you showed up".
    private long focusMinutes;
    private long tasksCompleted;
    private long workouts;
    private long learnings;

    // Consecutive days (ending today or yesterday) with at least one mind entry.
    private long streakDays;

    // Closing-the-loop counts for the week's captured thoughts.
    private long captured;
    private long converted;
    private long reframed;
    private long released;

    // Today's mood check-in (1–5), or null if not set.
    private Integer moodScore;
}
