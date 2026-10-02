package com.personal_dashboard.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import com.personal_dashboard.backend.model.GoalKit;

import java.util.List;
import java.util.Map;

/**
 * A goal's journey: every day it was tended, oldest first (util/GoalJourney). Drawn by the
 * goal's own world — The Quiet Path lays one stone per day. Mirrored by GoalJourney in
 * types/goals.ts; guest mode builds the same list (mocks/guest-goals.ts).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GoalJourneyResponse {

    private String goalId;

    /** The day it was read for (the client's local today). */
    private String date;

    private List<JourneyDay> days;

    /** What the user has packed in the world's kit, by page (model/GoalKit). */
    private Map<String, GoalKit.Page> kit;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class JourneyDay {
        private String date;
        /** Check-ins that day. */
        private int entries;
        /** Their summed value (minutes, for a goal counted in minutes). */
        private double value;
        /** Practice kinds logged that day, in the order first logged, without repeats. */
        private List<String> practices;
        /** The day's notes, oldest first — "what helped". */
        private List<String> notes;
        /** Guided sessions done that day, in the order done (repeats kept — a replay is a replay). */
        private List<String> sessions;
        /** Minutes logged that day (sessions and timed practices). */
        private int minutes;
    }
}
