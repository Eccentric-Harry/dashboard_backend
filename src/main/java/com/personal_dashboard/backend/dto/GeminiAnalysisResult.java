package com.personal_dashboard.backend.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Typed representation of the Stage 2 clinical nutrition analysis output.
 * Matches the deep-assessment JSON contract from the NutritionPipelineService Stage 2 prompt.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class GeminiAnalysisResult {

    /**
     * Compact arithmetic trace (macro→calorie sum, gate result, glycaemic load).
     *
     * <p>Replaces the former {@code _reasoning_scratchpad}, which asked the model to
     * transcribe a full seven-step chain of thought — hundreds of output tokens per
     * scan for a field that was never persisted onto the MealEntry or rendered. The
     * model still reasons internally; only the transcription was dropped.
     */
    @JsonProperty("_verification")
    private String verification;

    @JsonProperty("pipeline_stage")
    private String pipelineStage;

    @JsonProperty("meal_label")
    private String mealLabel;

    @JsonProperty("cuisine_type")
    private String cuisineType;

    @JsonProperty("meal_type")
    private String mealType;

    @JsonProperty("analysis_timestamp_utc")
    private String analysisTimestampUtc;

    @JsonProperty("macro_totals")
    private MacroTotals macroTotals;

    @JsonProperty("ingredients_breakdown")
    private List<IngredientBreakdown> ingredientsBreakdown;

    @JsonProperty("glycaemic_assessment")
    private GlycaemicAssessment glycaemicAssessment;

    @JsonProperty("daily_budget_analysis")
    private DailyBudgetAnalysis dailyBudgetAnalysis;

    @JsonProperty("clinical_flags")
    private List<ClinicalFlag> clinicalFlags;

    @JsonProperty("meal_score")
    private MealScore mealScore;

    @JsonProperty("recommendations")
    private List<Recommendation> recommendations;

    @JsonProperty("positive_highlights")
    private List<PositiveHighlight> positiveHighlights;

    @JsonProperty("next_meal_guidance")
    private NextMealGuidance nextMealGuidance;

    @JsonProperty("data_quality_flags")
    private List<String> dataQualityFlags;

    @JsonProperty("disclaimer")
    private String disclaimer;

    // ─── Nested POJOs ─────────────────────────────────────────────────

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class MacroTotals {
        @JsonProperty("calories_kcal")
        private double caloriesKcal;

        @JsonProperty("protein_g")
        private double proteinG;

        @JsonProperty("carbohydrates_g")
        private double carbohydratesG;

        @JsonProperty("fat_g")
        private double fatG;

        @JsonProperty("saturated_fat_g")
        private double saturatedFatG;

        @JsonProperty("unsaturated_fat_g")
        private double unsaturatedFatG;

        @JsonProperty("trans_fat_g")
        private double transFatG;

        @JsonProperty("dietary_fiber_g")
        private double dietaryFiberG;

        @JsonProperty("sugar_g")
        private double sugarG;

        @JsonProperty("added_sugar_g")
        private double addedSugarG;

        @JsonProperty("sodium_mg")
        private double sodiumMg;

        @JsonProperty("potassium_mg")
        private double potassiumMg;

        @JsonProperty("cholesterol_mg")
        private double cholesterolMg;

        @JsonProperty("math_verification")
        private MathVerification mathVerification;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class MathVerification {
        @JsonProperty("expected_calories_from_macros")
        private double expectedCaloriesFromMacros;

        @JsonProperty("stated_calories")
        private double statedCalories;

        @JsonProperty("delta_kcal")
        private double deltaKcal;

        @JsonProperty("gate_passed")
        private boolean gatePassed;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class IngredientBreakdown {
        @JsonProperty("item_id")
        private int itemId;

        @JsonProperty("name")
        private String name;

        @JsonProperty("common_name")
        private String commonName;

        @JsonProperty("estimated_weight_g")
        private double estimatedWeightG;

        @JsonProperty("is_hidden")
        private boolean isHidden;

        @JsonProperty("nutrition_per_100g_source")
        private String nutritionPer100gSource;

        @JsonProperty("nutrients")
        private IngredientNutrients nutrients;

        @JsonProperty("item_math_check")
        private ItemMathCheck itemMathCheck;

        @JsonProperty("glycaemic_index_estimate")
        private Double glycaemicIndexEstimate;

        @JsonProperty("glycaemic_load_contribution")
        private Double glycaemicLoadContribution;

        @JsonProperty("clinical_item_flags")
        private List<String> clinicalItemFlags;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class IngredientNutrients {
        @JsonProperty("calories_kcal")
        private double caloriesKcal;

        @JsonProperty("protein_g")
        private double proteinG;

        @JsonProperty("carbohydrates_g")
        private double carbohydratesG;

        @JsonProperty("fat_g")
        private double fatG;

        @JsonProperty("saturated_fat_g")
        private double saturatedFatG;

        @JsonProperty("dietary_fiber_g")
        private double dietaryFiberG;

        @JsonProperty("sugar_g")
        private double sugarG;

        @JsonProperty("sodium_mg")
        private double sodiumMg;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ItemMathCheck {
        @JsonProperty("expected_kcal")
        private double expectedKcal;

        @JsonProperty("stated_kcal")
        private double statedKcal;

        @JsonProperty("passed")
        private boolean passed;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class GlycaemicAssessment {
        @JsonProperty("total_meal_glycaemic_load")
        private double totalMealGlycaemicLoad;

        @JsonProperty("gl_classification")
        private String glClassification;

        @JsonProperty("insulin_impact_summary")
        private String insulinImpactSummary;

        @JsonProperty("highest_gi_offenders")
        private List<GiOffender> highestGiOffenders;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class GiOffender {
        @JsonProperty("ingredient_name")
        private String ingredientName;

        @JsonProperty("gi_estimate")
        private double giEstimate;

        @JsonProperty("gl_contribution")
        private double glContribution;

        @JsonProperty("clinical_note")
        private String clinicalNote;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class DailyBudgetAnalysis {
        @JsonProperty("remaining_budget_before_this_meal")
        private BudgetValues remainingBudgetBeforeThisMeal;

        @JsonProperty("remaining_budget_after_this_meal")
        private BudgetValues remainingBudgetAfterThisMeal;

        @JsonProperty("budget_status")
        private BudgetStatus budgetStatus;

        @JsonProperty("percentage_of_daily_goals_this_meal")
        private DailyGoalPercentages percentageOfDailyGoalsThisMeal;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class BudgetValues {
        @JsonProperty("calories_kcal")
        private double caloriesKcal;

        @JsonProperty("protein_g")
        private double proteinG;

        @JsonProperty("carbs_g")
        private double carbsG;

        @JsonProperty("fat_g")
        private double fatG;

        @JsonProperty("sodium_mg")
        private double sodiumMg;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class BudgetStatus {
        private String calories;
        private String protein;
        private String carbs;
        private String fat;
        private String sodium;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class DailyGoalPercentages {
        @JsonProperty("calories_pct")
        private double caloriesPct;

        @JsonProperty("protein_pct")
        private double proteinPct;

        @JsonProperty("carbs_pct")
        private double carbsPct;

        @JsonProperty("fat_pct")
        private double fatPct;

        @JsonProperty("sodium_pct")
        private double sodiumPct;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ClinicalFlag {
        @JsonProperty("flag_id")
        private String flagId;

        private String severity;
        private String category;

        @JsonProperty("condition_link")
        private String conditionLink;

        private String title;

        @JsonProperty("evidence_basis")
        private String evidenceBasis;

        @JsonProperty("mechanistic_pathway")
        private String mechanisticPathway;

        @JsonProperty("affected_ingredients")
        private List<String> affectedIngredients;

        @JsonProperty("quantified_risk")
        private String quantifiedRisk;

        private String urgency;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class MealScore {
        @JsonProperty("meal_context")
        private String mealContext;

        @JsonProperty("overall_score")
        private int overallScore;

        @JsonProperty("score_rationale")
        private String scoreRationale;

        @JsonProperty("macro_balance_score")
        private int macroBalanceScore;

        @JsonProperty("glycaemic_score")
        private int glycaemicScore;

        @JsonProperty("micronutrient_density_score")
        private int micronutrientDensityScore;

        @JsonProperty("condition_safety_score")
        private int conditionSafetyScore;

        @JsonProperty("letter_grade")
        private String letterGrade;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Recommendation {
        @JsonProperty("rec_id")
        private String recId;

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
    public static class PositiveHighlight {
        @JsonProperty("highlight_id")
        private String highlightId;

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
    public static class NextMealGuidance {
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
