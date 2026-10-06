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
 * The /goals camp's own state, one document per user: the buddy's name, the sparks
 * balance, what has been bought and what is worn, how far the camp chest has been
 * opened, and which daily quests have been claimed.
 *
 * <p>Progress itself is never stored here — kept weeks, quests and the season map are
 * all derived on read from goals and check-ins (util/CampRules). What lives here is only
 * what the user <em>did</em> with them: opened, claimed, bought, named, wore. Every write
 * is a conditional atomic update in {@code GoalCampService}, so a double tap can never
 * pay twice or spend sparks that aren't there.
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "goal_camps")
public class GoalCamp implements UserOwnedDocument {

    @Id
    private String id;

    /** Unique — MongoIndexInitializer's goal_camps.userId index is what keeps it one per user. */
    private String userId;

    /** What the buddy is called; null means the default, "Pip". */
    private String buddyName;

    /** The balance. Only a purchase the user chooses ever lowers it. */
    private int sparks;

    /** Every spark ever earned — only grows. */
    private int sparksEarned;

    /** Item ids bought from Fen's cart (util/CampRules.ITEMS). */
    private List<String> owned;

    /** Worn items by slot: hat, neck, face. */
    private Map<String, String> equipped;

    /** Owned camp decorations on show. */
    private List<String> decor;

    /** Where meadow decorations stand, by item id; one not listed stands in its default spot. */
    private Map<String, DecorSpot> decorAt;

    /** Goal id → how many of its kept weeks the chest has already paid out. */
    private Map<String, Integer> chestPaid;

    /** Claimed quest ids ("yyyy-MM-dd:slot"), pruned after a couple of weeks. */
    private List<String> questsClaimed;

    /**
     * Hoot's "first light": the lantern the user chose, the night before, to light first on
     * {@code date}. One at a time; a pick for a day that has passed is simply ignored on read.
     */
    private FirstLight firstLight;

    private Instant createdAt;

    private Instant updatedAt;

    /** The goal to light first on a given local day (yyyy-MM-dd). */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FirstLight {
        private String date;
        private String goalId;
    }

    /** A spot in the camp's meadow: fractions (0–1) across its width and down its depth. */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DecorSpot {
        private double x;
        private double y;
    }
}
