package com.personal_dashboard.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One day of the Loop Radar series: mind activity alongside the Life OS signals that
 * plausibly move it.
 *
 * This is deliberately flat, unranked data. All interpretation happens client-side in
 * lib/insights/mind.ts, matching the rest of the deterministic insights engine — no
 * correlation is asserted here, and none is inferred by a model anywhere.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LoopRadarDay {

    /** ISO date, YYYY-MM-DD. */
    private String date;

    // Mind activity, split by lane.
    private long problems;
    private long worries;
    private long intrusive;
    private long untriaged;

    /** Spiral Breaker sessions run that day. */
    private long spirals;

    // Signals from the rest of Life OS, any of which may be null when nothing was logged.
    private Double sleepHours;
    private Long focusMinutes;
    private Long tasksCompleted;
    private Long workouts;
    private Integer moodScore;
}
