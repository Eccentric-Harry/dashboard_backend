package com.personal_dashboard.backend.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.time.LocalDate;

/**
 * One logged piece of progress towards a {@link Goal}. A COUNT goal can take several a day
 * (two reading sittings add up); a CHECK goal takes at most one per day per
 * {@link #practice}, each with value 1 — the day is hit either way.
 *
 * {@link #date} is the user's local day as the client resolved it — a check-in made
 * before 04:00 defaults to the previous day in the UI, so a late-night chapter counts for
 * the evening it belongs to.
 *
 * source: "manual" today. Auto-sourced progress (focus, sleep, Strava, nutrition) is
 * planned as virtual check-ins computed on read, never written here.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "goal_checkins")
@CompoundIndex(name = "user_goal_date_idx", def = "{'userId': 1, 'goalId': 1, 'date': 1}")
public class GoalCheckIn implements UserOwnedDocument {

    @Id
    private String id;

    private String userId;

    private String goalId;

    private LocalDate date;

    private double value;

    private String note;

    /**
     * What kind of practice this was — breathe, walk, write, still, gratitude, nature, talk, learn,
     * other — for goals with a world that cares (The Quiet Path colours its stones by it).
     * Null when not said. A CHECK goal can take one check-in per practice per day.
     */
    private String practice;

    /**
     * A guided session from a goal world's course (The Quiet Path's "leaves-on-a-stream"),
     * when this check-in is that session done. Null for anything else.
     */
    private String session;

    /** How many minutes it took — a session's length, or a timed practice. Null if not timed. */
    private Integer minutes;

    private String source;

    @CreatedDate
    private Instant createdAt;
}
