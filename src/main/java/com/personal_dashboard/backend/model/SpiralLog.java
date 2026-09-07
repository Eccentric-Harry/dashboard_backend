package com.personal_dashboard.backend.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A completed (or abandoned) Spiral Breaker session, stored on a type = SPIRAL entry.
 *
 * solvableIn24h mirrors the CBT Worry Tree's single branching question: an actionable
 * problem goes to a task, a hypothetical one gets nothing to do — which is the answer.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SpiralLog {

    private Boolean solvableIn24h;

    /** DailyTask the user was handed back to, when they chose to return to one. */
    private String returnedToTaskId;

    private Integer durationSeconds;
}
