package com.personal_dashboard.backend.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
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
 * A personal goal on the /goals route — "read 10 pages a day", "move 3 times a week",
 * "learn 7 hours a week". Only the rule is stored; every judgement about it (today met,
 * this week kept, pace, weeks kept) is derived on read from its {@link GoalCheckIn}s by
 * {@link com.personal_dashboard.backend.util.GoalProgress}, so backfilling a day or
 * editing the target re-judges history rather than leaving stale verdicts behind.
 *
 * measure: COUNT (an amount in {@link #unit}) | CHECK (simply done)
 * period:  DAY (target applies per day; a week is kept on {@link #daysPerWeek} hit days)
 *          | WEEK (target applies to the Monday–Sunday week)
 * status:  ACTIVE | ARCHIVED — archiving is the soft-delete; the history stays.
 *
 * Stored as free strings (not Java enums) to mirror the TypeScript unions in
 * types/goals.ts, the same way MindEntry and DailyTask store theirs.
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "goals")
@CompoundIndex(name = "user_status_idx", def = "{'userId': 1, 'status': 1}")
public class Goal implements UserOwnedDocument {

    public static final String MEASURE_COUNT = "COUNT";
    public static final String MEASURE_CHECK = "CHECK";
    public static final String PERIOD_DAY = "DAY";
    public static final String PERIOD_WEEK = "WEEK";
    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_ARCHIVED = "ARCHIVED";

    @Id
    private String id;

    private String userId;

    private String title;

    /** Key into the UI's fixed icon set (book, dumbbell, …); unknown keys fall back to a target. */
    private String icon;

    /**
     * Key into the UI's candy palette (tangerine, mint, sky, grape, berry, lemon, teal).
     * Null means "pick one from the board order", so goals made before colours existed still
     * get one.
     */
    private String color;

    /**
     * Which world the goal has of its own, visited from the camp at /goals?world=&lt;id&gt;:
     * "path" (The Quiet Path, a journey) — or null for a lantern at camp only. The camp
     * keeps every goal either way.
     */
    private String world;

    private String measure;

    private String period;

    /**
     * Per day for DAY goals (always 1 for CHECK + DAY), per week for WEEK goals — the
     * number of days with a check-in for CHECK + WEEK, the week's sum for COUNT + WEEK.
     */
    private double target;

    /** COUNT only — "pages", "min", "km". */
    private String unit;

    /** DAY only — how many hit days keep the week (1–7). The slack is the point. */
    private Integer daysPerWeek;

    private String status;

    /** Weeks that end before this date are never judged; the week it falls in is prorated. */
    private LocalDate startDate;

    /** Board order, ascending. */
    private int order;

    @CreatedDate
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;

    @JsonIgnore
    public boolean isDayPeriod() {
        return PERIOD_DAY.equals(period);
    }

    @JsonIgnore
    public boolean isCount() {
        return MEASURE_COUNT.equals(measure);
    }
}
