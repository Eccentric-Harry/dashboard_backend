package com.personal_dashboard.backend.service.nutrition;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * The only thing the second model call still produces: prose.
 *
 * <p>Every number the user sees — calories, macros, sodium, glycaemic load, budget, score —
 * is computed in Java before this call is made, and the computed figures are handed to the
 * model as fact. It writes the explanation and the swaps, which is the part a language model
 * is actually better at than a rule engine.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class NarrativeResponse {

    @JsonProperty("insulin_impact_summary")
    private String insulinImpactSummary;

    @JsonProperty("recommendations")
    @Builder.Default
    private List<Recommendation> recommendations = List.of();

    @JsonProperty("positive_highlights")
    @Builder.Default
    private List<Highlight> positiveHighlights = List.of();

    @JsonProperty("next_meal_guidance")
    private NextMeal nextMealGuidance;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Recommendation {
        private String priority;
        private String type;
        private String title;
        private String action;
        private String rationale;
        private String example;
        @JsonProperty("condition_targeted")
        private String conditionTargeted;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Highlight {
        @JsonProperty("ingredient_or_aspect")
        private String ingredientOrAspect;
        private String benefit;
        private String evidence;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class NextMeal {
        @JsonProperty("suggested_calorie_range_kcal")
        private String suggestedCalorieRangeKcal;
        @JsonProperty("priority_nutrients_to_target")
        private List<String> priorityNutrientsToTarget;
        @JsonProperty("foods_to_favour")
        private List<String> foodsToFavour;
        @JsonProperty("foods_to_limit")
        private List<String> foodsToLimit;
        @JsonProperty("timing_recommendation")
        private String timingRecommendation;
        @JsonProperty("hydration_note")
        private String hydrationNote;
    }
}
