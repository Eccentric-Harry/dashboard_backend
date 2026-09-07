package com.personal_dashboard.backend.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * A prediction attached to a parked worry, and the verdict recorded when its review
 * date arrives. This is the "worry outcome monitoring" instrument: the user records
 * what they fear and how likely it feels, then later records what actually happened.
 *
 * The point is not the individual row — it's the accumulating personal statistic that
 * catastrophic predictions almost never land, which is far more persuasive than being
 * told so.
 *
 * outcome:  NOT_HAPPENED | PARTLY | HAPPENED   (null until the verdict is given)
 * severity: BETTER | AS_FEARED | WORSE         (only meaningful when it did happen)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorryPrediction {

    /** The feared outcome in the user's own words, one line. */
    private String fearedOutcome;

    /** Gut-feel likelihood at park time, 0-100. */
    private Integer predictedProbability;

    private String outcome;

    private String severity;

    /** When the verdict was recorded. Null while still awaiting one. */
    private Instant recordedAt;
}
