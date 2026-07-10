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
 * One night of sleep, keyed by the wake-up date (unique per user + date).
 * Distinct from the legacy DailyHealthRecord.sleepHours, which has no write
 * path and only feeds the old dashboard payload.
 *
 * source: "manual" | "wearable" — kept as a free string to mirror the
 * TypeScript union in api.ts. TODO: wearable sync (Apple Health / Google Fit /
 * Strava sleep) will write source="wearable" through SleepService.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "sleep_logs")
@CompoundIndex(name = "user_date_unique", def = "{'userId': 1, 'date': 1}", unique = true)
public class SleepLog implements UserOwnedDocument {

    @Id
    private String id;

    private String userId;

    /** The morning this night of sleep ended on. */
    private LocalDate date;

    /** "HH:mm" — may be before or after midnight relative to {@link #date}. */
    private String bedtime;

    /** "HH:mm" on the morning of {@link #date}. */
    private String wakeTime;

    /** Computed server-side from bedtime → wakeTime (overnight-aware). */
    private int durationMinutes;

    /** 1 (rough) … 5 (great). */
    private Integer quality;

    private String note;

    private String source;

    @CreatedDate
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;
}
