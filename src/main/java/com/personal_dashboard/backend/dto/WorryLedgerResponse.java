package com.personal_dashboard.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * The accumulating case against the user's own catastrophising.
 *
 * Worry outcome monitoring works because the disconfirmations are personal and
 * counted, not because someone reassures you. The calibration pair below —
 * meanPredictedProbability against actualOccurrenceRate — is the whole point of the
 * instrument: the gap between what the gut insisted and what reality delivered.
 *
 * Counts are over entries carrying a prediction. Rates are null until there is at
 * least one verdict, so the UI can show an honest empty state rather than 0%.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorryLedgerResponse {

    /** Worries logged with a prediction, verdict or not. */
    private long totalPredicted;

    /** Of those, how many have had their verdict recorded. */
    private long totalResolved;

    private long notHappened;
    private long partly;
    private long happened;

    /** Mean gut-feel likelihood at park time, 0-100. Null when nothing is predicted yet. */
    private Double meanPredictedProbability;

    /**
     * Share of resolved worries that actually landed, 0-100. PARTLY counts as half —
     * it would overstate the case to call a partial hit nothing, and overstating is
     * exactly what would make this instrument untrustworthy to the person using it.
     */
    private Double actualOccurrenceRate;

    // Of the worries that did happen, how bad it turned out relative to the fear.
    private long copedBetter;
    private long copedAsFeared;
    private long copedWorse;
}
