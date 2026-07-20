package com.personal_dashboard.backend.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.time.LocalDate;

/**
 * One "ring day" of the Three Non-Negotiables — REST (sleep), DEEP (focus),
 * MOVE (workout) — computed from the day's source data, never hand-edited.
 * A ring day rolls over at the user's rollover hour (04:00 by default), not
 * midnight, so work finished at 01:00 still belongs to the previous day.
 *
 * The document is a cache of a pure computation: {@code RingsService.computeDay}
 * can rebuild any day from sleep_logs / focus_sessions / strava_activities at
 * any time. The only user-authored state here is the manual move log
 * (manualMoveMinutes / manualMoveType / moveNote), which survives recomputes.
 *
 * frozen and xpEarned are finalized by the streak replay
 * ({@code RingsService.recomputeStreak}) — a frozen day protects the chain but
 * earns no XP and never counts as perfect.
 *
 * moveSource: "STRAVA" | "MANUAL" | null — whichever source won the max.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "daily_rings")
@CompoundIndex(name = "user_date_unique", def = "{'userId': 1, 'date': 1}", unique = true)
public class DailyRing implements UserOwnedDocument {

    @Id
    private String id;

    private String userId;

    private LocalDate date;

    private int restMinutes;
    private int restTargetMinutes;
    private boolean restClosed;

    private int deepMinutes;
    private int deepTargetMinutes;
    private boolean deepClosed;

    private int moveMinutes;
    private int moveTargetMinutes;
    private boolean moveClosed;

    private String moveSource;

    private String moveNote;

    /** The manual "I moved" log for this day — preserved across recomputes. */
    private Integer manualMoveMinutes;

    /** Walk | Run | Gym | Cycle | Other — free string, mirrors the TS union. */
    private String manualMoveType;

    /** How many of the three rings closed (0..3). */
    private int ringsClosed;

    private boolean perfect;

    /** A streak freeze covered this day — chain held, no XP, not perfect. */
    private boolean frozen;

    /**
     * Fuel XP (Phase 2): +10 per logged meal, +25 when the day's protein goal
     * was met — recomputed from daily_food_logs, folded into xpEarned by the
     * streak replay (and zeroed with the rest when the day is frozen).
     */
    private int fuelXp;

    private int xpEarned;

    private Instant computedAt;

    @CreatedDate
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;
}
