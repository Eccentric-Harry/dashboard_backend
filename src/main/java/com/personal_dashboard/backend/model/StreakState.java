package com.personal_dashboard.backend.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.time.LocalDate;

/**
 * One document per user: the streak, freeze, and XP ledger for the Three
 * Non-Negotiables. Every field is derived — {@code RingsService.recomputeStreak}
 * replays the full daily_rings history on read and overwrites this document,
 * so it can never drift from the underlying data. Never mutate it incrementally.
 *
 * A day qualifies when all three rings closed. Today-in-progress never breaks
 * the streak (it is "at risk", not lost, until the 04:00 rollover). Two freezes
 * per calendar month cover past unqualified days without resetting the chain;
 * a frozen day holds the streak but does not extend it.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "streak_states")
public class StreakState implements UserOwnedDocument {

    @Id
    private String id;

    @Indexed(unique = true)
    private String userId;

    private int currentStreak;
    private int longestStreak;

    private LocalDate lastPerfectDate;

    private int freezesAvailable;
    private int freezesUsedThisMonth;

    /** "yyyy-MM" of the month freezesAvailable/freezesUsedThisMonth refer to. */
    private String freezeMonthKey;

    private long totalXp;

    /** level = floor(sqrt(totalXp / 100)) + 1 — legible, monotonic. */
    private int level;

    private int perfectDaysAllTime;

    @LastModifiedDate
    private Instant updatedAt;
}
