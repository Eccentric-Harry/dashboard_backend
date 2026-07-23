package com.personal_dashboard.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response DTO for the POST /api/v1/meals/analyze endpoint.
 * Returned after the two-stage Gemini pipeline completes and the meal is persisted.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MealAnalysisResponse {

    /** The MongoDB document ID of the saved MealEntry */
    private String mealEntryId;

    /** Meal type that was used to bucket the entry (e.g., "Lunch") */
    private String mealType;

    /** Date the meal was logged (YYYY-MM-DD) */
    private String date;

    /** Auto-generated description from Gemini Stage 1 items */
    private String description;

    /** Calories saved to the MealEntry */
    private int calories;

    /** Protein grams saved to the MealEntry */
    private int proteinGrams;

    /** AI-generated pastel dish image as a data: URI (null if generation was skipped/failed) */
    private String imageUrl;

    /** Full Gemini Stage 2 analysis result for the frontend results panel */
    private GeminiAnalysisResult analysis;
}
