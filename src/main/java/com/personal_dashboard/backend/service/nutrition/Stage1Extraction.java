package com.personal_dashboard.backend.service.nutrition;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Output contract of the vision stage: what food is present and how much of it.
 *
 * <p>Deliberately carries no calorie or macronutrient fields. The vision model is asked only
 * to identify ingredients and estimate gram weights — the two things it is measurably good at
 * — and every nutrient number is looked up downstream from USDA data.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class Stage1Extraction {

    /**
     * Free-text observation the model writes before filling any structured field.
     * Exists because constrained decoding measurably degrades reasoning when the model has
     * to commit to schema fields before describing what it is looking at.
     */
    @JsonProperty("visual_assessment")
    private String visualAssessment;

    /**
     * The model's holistic energy estimate for the dish, made independently of its own
     * ingredient list. Never reported as the answer — used only to detect when ingredient
     * decomposition has missed something large.
     */
    @JsonProperty("dish_level_energy_estimate_kcal")
    private Double dishLevelEnergyEstimateKcal;

    @JsonProperty("meal_label")
    private String mealLabel;

    @JsonProperty("cuisine_type")
    private String cuisineType;

    @JsonProperty("meal_type_guess")
    private String mealTypeGuess;

    @JsonProperty("image_quality")
    private String imageQuality;

    @JsonProperty("extraction_confidence")
    private String extractionConfidence;

    @JsonProperty("extraction_notes")
    private String extractionNotes;

    @JsonProperty("ingredients")
    @Builder.Default
    private List<Item> ingredients = List.of();

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Item {

        @JsonProperty("item_id")
        private int itemId;

        @JsonProperty("usda_food_description")
        private String usdaFoodDescription;

        @JsonProperty("fdc_id")
        private Integer fdcId;

        @JsonProperty("common_name")
        private String commonName;

        @JsonProperty("estimated_weight_g")
        private double estimatedWeightG;

        @JsonProperty("confidence_range_g")
        private Range confidenceRangeG;

        @JsonProperty("confidence_score")
        private double confidenceScore;

        @JsonProperty("is_hidden")
        private boolean hidden;

        @JsonProperty("cooking_method")
        private String cookingMethod;

        @JsonProperty("item_notes")
        private String itemNotes;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Range {
        @JsonProperty("low")
        private double low;
        @JsonProperty("high")
        private double high;
    }
}
