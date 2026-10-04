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

/**
 * One logged action on a {@link Program} track: a run, a lift, a mood check-in, a kept
 * promise, minutes spoken, a day's screen minutes. Several per day are allowed (two kept
 * promises, two speaking sessions); the client folds a day's logs into one status.
 *
 * <p>{@link #level}: FULL (the full version), MIN (the bad-day version — still done), REST
 * (a planned rest day, logged rather than hidden), or null for evidence that isn't a
 * completion — an urge ridden out, a meeting stretch, a recording.
 *
 * <p>The typed optional fields are the brief's "details": only the ones a track uses are set.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "program_logs")
public class ProgramLog implements UserOwnedDocument {

    @Id
    private String id;

    private String userId;

    private String programId;

    private String track;

    /** The user's local day, yyyy-MM-dd as the client resolved it (04:00 rollover). */
    private LocalDate date;

    /** FULL, MIN, REST, or null. */
    private String level;

    /** Screen minutes, mood score 1–5, protein grams (manual), speaking minutes. */
    private Double value;

    private Integer minutes;

    private Double distanceKm;

    /** How it felt, 1–5 (runs). */
    private Integer feel;

    /** Lifts: one top set per exercise. */
    private List<LiftSet> sets;

    /** A one-word mood tag, or work/home for Learn. */
    private String tag;

    private String note;

    /** Self-regard: the promise kept. Learn: the one-line takeaway. */
    private String text;

    /** Self-regard: the kind sentence. */
    private String kind;

    private String pursuitId;

    /** A plan session: c25k-3-2 (week 3, run 2), lift-a, shadow… */
    private String session;

    /** Screen: an urge ridden out. */
    private Boolean urge;

    /** Screen: no phone for the first 30 minutes after waking. */
    private Boolean morningRule;

    /** Screen: no phone for the last 30 minutes before sleep. */
    private Boolean nightRule;

    /** English: this week's meeting stretch (speaking up once). */
    private Boolean stretch;

    private Instant createdAt;

    private Instant updatedAt;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LiftSet {
        private String exercise;
        /** 0 for bodyweight. */
        private Double weightKg;
        private Integer reps;
    }
}
