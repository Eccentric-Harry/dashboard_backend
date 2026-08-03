package com.personal_dashboard.backend.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;

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

    /**
     * Optional AI-generated pastel dish image, stored as a self-contained data: URI.
     * Populated by the AI meal-scan pipeline; null for manual entries (which fall back
     * to the keyword-matched bundled asset on the frontend).
     */
    private String imageUrl;

    private String notes;

    private String recipeCategory;

    private String serving;

    private String servingNotes;

    private String sourceNotes;

    /** Used for CSV import deduplication */
    private String importKey;

    /** When the meal was logged */
    private Instant timestamp;

    // Rich Detailed Nutrition Payload
    private Map<String, Object> analysisMetadata;
    private List<Map<String, Object>> mealItems;
    private Map<String, Object> totalSummary;
    private List<Object> gapsAndWarnings;
    private Map<String, Object> technicalDiagnostic;
    private Map<String, Object> acneImpactAssessment;
    private Map<String, Object> healthAnalysis;
    private Map<String, Object> recompositionAssessment;
    private Map<String, Object> satietyAndEnergyProfile;
    private Map<String, Object> nutritionalBalanceDiagnostic;
    private Map<String, Object> dailyContext;

    /**
     * What this analysis cost to produce: token counts and price, broken down by pipeline
     * stage. Stored per meal so spend is attributable to the scan that incurred it, and so
     * a month's API bill can be reconstructed from the log rather than estimated.
     * Null for meals logged manually or before cost tracking existed.
     */
    private Map<String, Object> apiCost;

    @CreatedDate
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;
}
