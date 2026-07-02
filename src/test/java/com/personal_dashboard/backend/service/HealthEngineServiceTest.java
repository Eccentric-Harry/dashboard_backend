package com.personal_dashboard.backend.service;

import com.personal_dashboard.backend.model.ActivityLevel;
import com.personal_dashboard.backend.model.FitnessGoal;
import com.personal_dashboard.backend.model.UserAccount;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class HealthEngineServiceTest {

    private HealthEngineService healthEngineService;

    @BeforeEach
    void setUp() {
        healthEngineService = new HealthEngineService();
    }

    @Test
    void testMaleCalorieAndMacrosCalculations() {
        UserAccount user = UserAccount.builder()
                .physicalMetrics(UserAccount.PhysicalMetrics.builder()
                        .age(28)
                        .gender("MALE")
                        .height(180.0)
                        .weight(80.0)
                        .build())
                .activityLevel(ActivityLevel.MODERATELY_ACTIVE)
                .fitnessGoal(FitnessGoal.LOSE_WEIGHT)
                .medicalConditions(List.of("Lactose Intolerance"))
                .build();

        healthEngineService.calculateHealthMetrics(user);

        // BMI = 80 / (1.8 * 1.8) = 24.69
        assertEquals(24.7, user.getBmi());

        // BMR = 10 * 80 + 6.25 * 180 - 5 * 28 + 5 = 800 + 1125 - 140 + 5 = 1790.0
        assertEquals(1790.0, user.getBmr());

        // TDEE = 1790.0 * 1.55 = 2774.5
        assertEquals(2774.5, user.getTdee());

        // Target Calories (LOSE_WEIGHT: TDEE - 500) = 2774.5 - 500 = 2274.5 -> rounded to 2275
        assertEquals(2275, user.getDynamicTargets().getCalculatedCalories());

        // Legacy top-level syncs
        assertEquals(2275, user.getTargetCalories());

        // Protein = 2.0g per kg = 2.0 * 80 = 160g
        assertEquals(160, user.getDynamicTargets().getCalculatedProtein());
        assertEquals(160, user.getTargetProtein());

        // Fat = 25% of Target Calories = 2275 * 0.25 = 568.75 kcal. 1g fat = 9 kcal -> 568.75 / 9 = 63.19 -> 63
        assertEquals(63, user.getDynamicTargets().getCalculatedFat());

        // Carbs = Remaining kcal = 2275 - (160 * 4) - (63 * 9) = 2275 - 640 - 567 = 1068 kcal. 1g carb = 4 kcal -> 1068 / 4 = 267
        assertEquals(267, user.getDynamicTargets().getCalculatedCarbs());
    }

    @Test
    void testFemaleCalorieAndMacrosCalculations() {
        UserAccount user = UserAccount.builder()
                .physicalMetrics(UserAccount.PhysicalMetrics.builder()
                        .age(28)
                        .gender("FEMALE")
                        .height(165.0)
                        .weight(60.0)
                        .build())
                .activityLevel(ActivityLevel.LIGHTLY_ACTIVE)
                .fitnessGoal(FitnessGoal.GAIN_MUSCLE)
                .build();

        healthEngineService.calculateHealthMetrics(user);

        // BMI = 60 / (1.65 * 1.65) = 22.038 -> 22.0
        assertEquals(22.0, user.getBmi());

        // BMR = 10 * 60 + 6.25 * 165 - 5 * 28 - 161 = 600 + 1031.25 - 140 - 161 = 1330.25 -> 1330.3
        assertEquals(1330.3, user.getBmr());

        // TDEE = 1330.25 * 1.375 = 1829.09 -> 1829.1
        assertEquals(1829.1, user.getTdee());

        // Target Calories (GAIN_MUSCLE: TDEE + 300) = 1829.09 + 300 = 2129.09 -> 2129
        assertEquals(2129, user.getDynamicTargets().getCalculatedCalories());

        // Protein = 2.0 * 60 = 120
        assertEquals(120, user.getDynamicTargets().getCalculatedProtein());

        // Fat = 25% of calories = 2129 * 0.25 = 532.25 kcal / 9 = 59.13g -> 59
        assertEquals(59, user.getDynamicTargets().getCalculatedFat());

        // Carbs = Remaining = 2129 - (120 * 4) - (59 * 9) = 2129 - 480 - 531 = 1118 kcal / 4 = 279.5g -> 280
        assertEquals(280, user.getDynamicTargets().getCalculatedCarbs());
    }
}
