package com.personal_dashboard.backend.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Embedded POJO representing a single meal entry within a DailyFoodLog.
 * Not a top-level @Document — lives nested inside DailyFoodLog.meals.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MealEntry {

    /** Auto-generated UUID for uniquely identifying this meal within a day */
    private String id;

    private String description;

    private Integer calories;

    private Integer proteinGrams;

    private String mealQuality;

    private String notes;

    private String recipeCategory;

    private String serving;

    private String servingNotes;

    private String sourceNotes;

    /** Used for CSV import deduplication */
    private String importKey;

    /** When the meal was logged */
    private Instant timestamp;
}
