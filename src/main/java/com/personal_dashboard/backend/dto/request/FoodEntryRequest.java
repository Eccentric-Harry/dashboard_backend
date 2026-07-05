package com.personal_dashboard.backend.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FoodEntryRequest {

    @NotBlank(message = "Description is required")
    private String description;

    @NotNull(message = "Calories is required")
    @Min(value = 0, message = "Calories must be non-negative")
    @Max(value = 10000, message = "Calories must be less than 10000")
    private Integer calories;

    @NotNull(message = "Protein grams is required")
    @Min(value = 0, message = "Protein grams must be non-negative")
    @Max(value = 500, message = "Protein grams must be less than 500")
    private Integer proteinGrams;

    @NotBlank(message = "Meal type is required")
    @Pattern(regexp = "Breakfast|Lunch|Dinner|Snack|Midnight|Post Workout|Mid-Morning", message = "Meal type must be one of: Breakfast, Lunch, Dinner, Snack, Midnight, Post Workout, Mid-Morning")
    private String mealType;

    @NotBlank(message = "Date is required")
    @Pattern(regexp = "\\d{4}-\\d{2}-\\d{2}", message = "Date must be in format YYYY-MM-DD")
    private String date;

    @JsonProperty("analysis_metadata")
    private Map<String, Object> analysisMetadata;

    @JsonProperty("meal_items")
    private List<Map<String, Object>> mealItems;

    @JsonProperty("total_summary")
    private Map<String, Object> totalSummary;

    @JsonProperty("gaps_and_warnings")
    private List<Object> gapsAndWarnings;

    @JsonProperty("technical_diagnostic")
    private Map<String, Object> technicalDiagnostic;

    @JsonProperty("acne_impact_assessment")
    private Map<String, Object> acneImpactAssessment;

    @JsonProperty("health_analysis")
    private Map<String, Object> healthAnalysis;

    @JsonProperty("recomposition_assessment")
    private Map<String, Object> recompositionAssessment;

    @JsonProperty("satiety_and_energy_profile")
    private Map<String, Object> satietyAndEnergyProfile;

    @JsonProperty("nutritional_balance_diagnostic")
    private Map<String, Object> nutritionalBalanceDiagnostic;

    @JsonProperty("daily_context")
    private Map<String, Object> dailyContext;

    @JsonProperty("meal_quality")
    private String mealQuality;

    private String notes;

    @JsonProperty("recipe_category")
    private String recipeCategory;

    private String serving;

    @JsonProperty("serving_notes")
    private String servingNotes;

    @JsonProperty("source_notes")
    private String sourceNotes;

    @JsonProperty("import_key")
    private String importKey;

    private java.time.Instant timestamp;
}
