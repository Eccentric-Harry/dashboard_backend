package com.personal_dashboard.backend.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Embedded POJO for hydration data within DailyFoodLog.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HydrationData {

    @Builder.Default
    private Double waterIntakeMl = 0.0;

    @Builder.Default
    private Double targetMl = 4000.0;

    private String notes;
}
