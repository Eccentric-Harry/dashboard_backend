package com.personal_dashboard.backend.dto;

import com.personal_dashboard.backend.model.ProgramAssessment;
import com.personal_dashboard.backend.model.ProgramLog;
import com.personal_dashboard.backend.model.ProgramReview;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * GET /program — everything The Lighthouse needs in one read: the active program (null when
 * none has begun), every log, review, assessment and media item on it, and what the rest of
 * Life OS already knows for those days, so nothing is logged twice.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProgramStateResponse {

    private ProgramView program;
    private List<ProgramLog> logs;
    private List<ProgramReview> reviews;
    private List<ProgramAssessment> assessments;
    private List<ProgramMediaView> media;
    private Sources sources;

    /**
     * Read-only data from other routes, keyed by local day (yyyy-MM-dd). A day missing from a
     * map is unknown — never zero (focus capture integrity).
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Sources {
        /** /nutrition: protein grams on days that have food logged. */
        private Map<String, Integer> protein;
        /** /nutrition's own daily protein goal, for reference. */
        private Integer proteinGoal;
        /** /home: sleep minutes by wake-up day. */
        private Map<String, Integer> sleep;
        /** /mind: mood check-ins, 1–5. */
        private Map<String, Integer> mood;
        /** /workouts: runs from Strava. */
        private Map<String, RunDay> runs;
        /** /learnings: completed focus minutes. */
        private Map<String, Integer> focus;
        /** The profile's body weight, to prefill the protein maths. */
        private Double profileWeightKg;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RunDay {
        private int count;
        private double km;
        private double minutes;
    }
}
