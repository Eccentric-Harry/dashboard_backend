package com.personal_dashboard.backend.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
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
public class FoodEntryDTO {

    private String id;

    private String description;

    private Integer calories;

    private Integer proteinGrams;

    private String mealType;

    private String date;

    private String mealQuality;

    private String notes;

    private String recipeCategory;

    private String serving;

    private String servingNotes;

    private String sourceNotes;

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
}
