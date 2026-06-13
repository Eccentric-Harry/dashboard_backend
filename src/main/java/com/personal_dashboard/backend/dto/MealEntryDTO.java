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
public class MealEntryDTO {

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

    private String timestamp;

    @JsonProperty("analysis_metadata")
    private Map<String, Object> analysisMetadata;

    @JsonProperty("meal_items")
    private List<Map<String, Object>> mealItems;

    @JsonProperty("total_summary")
    private Map<String, Object> totalSummary;

    @JsonProperty("gaps_and_warnings")
    private List<String> gapsAndWarnings;

    @JsonProperty("technical_diagnostic")
    private Map<String, Object> technicalDiagnostic;
}
