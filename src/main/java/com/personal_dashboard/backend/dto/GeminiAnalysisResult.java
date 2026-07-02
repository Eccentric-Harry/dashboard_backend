package com.personal_dashboard.backend.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Typed representation of the Gemini Stage 2 clinical nutrition analysis output.
 * Matches the exact JSON contract from the GeminiNutritionService pipeline.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class GeminiAnalysisResult {

    @JsonProperty("meal_items")
    private List<MealItemResult> mealItems;

    @JsonProperty("meal_totals")
    private MealTotals mealTotals;

    @JsonProperty("daily_target_progress")
    private DailyTargetProgress dailyTargetProgress;

    @JsonProperty("medical_analysis")
    private List<MedicalAnalysisItem> medicalAnalysis;

    @JsonProperty("overall_assessment")
    private OverallAssessment overallAssessment;

    // ─── Nested POJOs ─────────────────────────────────────────────────

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class MealItemResult {
        private String name;

        @JsonProperty("serving_size")
        private String servingSize;

        private String confidence;
        private double calories;
        private double protein;
        private double carbs;
        private double fat;
        private double fiber;
        private double sugar;
        private double sodium;

        @JsonProperty("saturated_fat")
        private double saturatedFat;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class MealTotals {
        private double calories;
        private double protein;
        private double carbs;
        private double fat;
        private double fiber;
        private double sugar;
        private double sodium;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class DailyTargetProgress {
        @JsonProperty("calories_pct")
        private double caloriesPct;

        @JsonProperty("protein_pct")
        private double proteinPct;

        @JsonProperty("carbs_pct")
        private double carbsPct;

        @JsonProperty("fat_pct")
        private double fatPct;

        @JsonProperty("fiber_pct")
        private double fiberPct;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class MedicalAnalysisItem {
        private String condition;
        private String risk;
        private List<String> findings;
        private List<String> recommendations;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class OverallAssessment {
        @JsonProperty("meal_quality")
        private String mealQuality;

        @JsonProperty("fitness_alignment")
        private String fitnessAlignment;

        private List<String> strengths;
        private List<String> concerns;
        private List<String> improvements;
    }
}
