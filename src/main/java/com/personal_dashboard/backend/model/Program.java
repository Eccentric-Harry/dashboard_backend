package com.personal_dashboard.backend.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * A fixed-length self-improvement program on /goals — the user's "90 days to 23", lived in
 * The Lighthouse world (design/LIGHTHOUSE_90_PLAN.md). One ACTIVE program per user.
 *
 * <p>Only the plan is stored here: dates, the eight tracks' targets and if-then plans, the
 * user's answers and their letters. Everything judged from it — the day, the phase, what's
 * done, consistency, the screen cap — is derived by the client from the program's logs
 * (features/goals/lighthouse/program-engine.ts), so editing history fixes every view.
 *
 * <p>Targets change only through the weekly review ({@code ProgramService#saveReview}); the
 * settings endpoint deliberately can't touch them, so a change is always a decision.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "programs")
public class Program implements UserOwnedDocument {

    public static final String ACTIVE = "ACTIVE";
    public static final String ENDED = "ENDED";

    @Id
    private String id;

    private String userId;

    private String title;

    /** ACTIVE or ENDED. */
    private String status;

    /** Day 1. */
    private LocalDate startDate;

    /** The last day, inclusive — the birthday for "90 days to 23". */
    private LocalDate endDate;

    /** When sealed letters open; usually the end date. */
    private LocalDate birthday;

    /** Body weight the protein target was computed from (g per kg). */
    private Double weightKg;

    /** gym or home — which lifting plan to show. */
    private String liftPlace;

    /** The eight tracks in display order (keys are the client's content). */
    private List<Track> tracks;

    /** The user's own answers from the brief: what cheap dopamine means to them, etc. */
    private Map<String, String> answers;

    /** to23 (sealed until the birthday), from23 (always open), to24 (sealed a year on). */
    private Map<String, Letter> letters;

    private Instant createdAt;

    private Instant updatedAt;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Track {
        /** run, lift, protein, mood, learn, english, screen, regard. */
        private String key;
        /** Sessions or days per week, grams, minutes, or a cap; null = derived (screen) or none (mood). */
        private Double target;
        /** The bad-day floor where the track has a number for it. */
        private Double floor;
        /** The user's if-then plan: "If it's 7am on Mon/Wed/Fri, then shoes on and out the door." */
        private String plan;
        /**
         * Set when the user starts a track before its scheduled day ("Start it now"): it's on
         * from this date. Null keeps the schedule. Never later than the scheduled day.
         */
        private LocalDate openedOn;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Letter {
        private String text;
        private Instant writtenAt;
        /** Null = readable now. The server never returns the text before this day. */
        private LocalDate opensOn;
    }
}
