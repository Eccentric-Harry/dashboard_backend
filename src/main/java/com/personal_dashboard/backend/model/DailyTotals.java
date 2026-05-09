package com.personal_dashboard.backend.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Embedded POJO holding aggregated daily nutrition totals.
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
}
