package com.personal_dashboard.backend.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * A goal world's kit — the things the user packs along The Quiet Path: their early signs,
 * the breath that works for them, their anchors, kind words, wind-down, a heavy-day plan,
 * what matters to them. One document per (user, goal), written a page at a time by
 * {@link com.personal_dashboard.backend.service.GoalKitService}.
 *
 * <p>Kept apart from {@link Goal} on purpose: the board reads every goal on each visit to
 * the camp, and this is private writing that only the goal's own world needs.
 *
 * <p>Page keys and what the picks/fields mean are the client's content
 * (worlds/quiet-path/kit.ts); the server stores them without interpreting them.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "goal_kits")
public class GoalKit implements UserOwnedDocument {

    @Id
    private String id;

    private String userId;

    private String goalId;

    /** Page key (signs, breath, heavy-day…) → what's on it. */
    private Map<String, Page> pages;

    private Instant updatedAt;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Page {
        /** Suggestions the user picked, in the order picked. */
        private List<String> picks;
        /** Their own words, by field key. */
        private Map<String, String> fields;
        private Instant updatedAt;
    }
}
