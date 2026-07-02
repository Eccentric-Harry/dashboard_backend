package com.personal_dashboard.backend.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Embedded POJO holding aggregated daily nutrition totals.
 * Extended to include full macro/micro fields from the Gemini AI pipeline.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DailyTotals {

    @Builder.Default
    private Integer totalCalories = 0;

    @Builder.Default
    private Integer totalProteinGrams = 0;

    @Builder.Default
    private Integer totalCarbsGrams = 0;

    @Builder.Default
    private Integer totalFatGrams = 0;

    @Builder.Default
    private Double totalFiberGrams = 0.0;

    @Builder.Default
    private Double totalSugarGrams = 0.0;

    @Builder.Default
    private Double totalSodiumMg = 0.0;
}
