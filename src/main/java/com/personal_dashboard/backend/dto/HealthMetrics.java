package com.personal_dashboard.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HealthMetrics {

    private DailyFoodIntake dailyFood;

    private List<CircularProgressMetric> circularGoals;

    private List<SleepEntry> sleepHours;

    private List<WeightEntry> weightTrend;

    private List<FoodEntryDTO> foodEntries;
}
