package com.personal_dashboard.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DailyFoodIntake {

    private String date;

    private Integer calories;

    private Integer calorieGoal;

    private Integer proteinGrams;

    private Integer proteinGoalGrams;
}
