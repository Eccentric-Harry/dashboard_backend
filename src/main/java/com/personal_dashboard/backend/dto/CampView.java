package com.personal_dashboard.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * The camp around the /goals board: sparks, what the buddy wears, the chest waiting to be
 * opened, today's quests from Wren and the season map. Derived on read by util/CampRules
 * from goals, check-ins and the user's goal_camps document. Mirrored by CampView in
 * types/goals.ts; guest mode ports the same rules (mocks/guest-goals.ts).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CampView {

    /** Null means the default name, "Pip". */
    private String buddyName;

    private int sparks;

    private int sparksEarned;

    private List<String> owned;

    /** slot (hat, neck, face) → item id. */
    private Map<String, String> equipped;

    private List<String> decor;

    /** One entry per goal with kept weeks the chest hasn't paid out yet; empty = nothing inside. */
    private List<ChestItem> chest;

    /** Today's three quests. */
    private List<Quest> quests;

    /** Yesterday's quests that are done but not yet claimed — still claimable today. */
    private List<Quest> questsYesterday;

    private Season season;

    /**
     * Lifetime kept weeks the buddy grows from: every active goal's kept weeks, plus what the
     * chest already paid for goals since archived — so packing a goal away never shrinks it.
     */
    private int grownWeeks;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ChestItem {
        private String goalId;
        private String title;
        private String icon;
        private String color;
        /** Kept weeks not yet paid out. */
        private int weeks;
        /** Sparks for those weeks, sticker bonuses included. */
        private int sparks;
        /** Stickers newly earned with these weeks. */
        private List<Sticker> stickers;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Sticker {
        /** The tier: 1, 4, 12, 26 or 52 kept weeks. */
        private int weeks;
        private String name;
        private int bonus;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Quest {
        /** "yyyy-MM-dd:slot" — the claim key. */
        private String id;
        private String date;
        /** light-n | light-goal | note | early | amount | all — the UI writes the words. */
        private String kind;
        private String goalId;
        private String goalTitle;
        /** amount quests: how much, in the goal's unit. */
        private Double amount;
        private String unit;
        private double progress;
        private double target;
        private int reward;
        private boolean done;
        private boolean claimed;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Season {
        /** "autumn-2026" */
        private String key;
        /** winter | spring | summer | autumn */
        private String name;
        private String start;
        private String end;
        private List<SeasonWeek> weeks;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SeasonWeek {
        private String weekStart;
        /** Active goals that existed by this week. */
        private int goals;
        /** Of those, how many kept it (the current week counts once kept). */
        private int kept;
        private boolean current;
        private boolean future;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ChestOpenResult {
        /** What came out — empty when someone else (another tab) opened it first. */
        private List<ChestItem> opened;
        private int sparks;
        private CampView camp;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class QuestClaimResult {
        /** Sparks this claim added — 0 if it was already claimed. */
        private int reward;
        private CampView camp;
    }
}
