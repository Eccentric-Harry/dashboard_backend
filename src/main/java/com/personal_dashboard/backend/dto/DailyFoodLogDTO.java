package com.personal_dashboard.backend.dto;

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
public class DailyFoodLogDTO {

    private String mealId;

    private String date;

    private DailyTotalsDTO dailyTotals;

    private Map<String, List<MealEntryDTO>> meals;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DailyTotalsDTO {
        private Integer totalCalories;
        private Integer totalProteinGrams;
    }
}
