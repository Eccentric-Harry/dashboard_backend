package com.personal_dashboard.backend.service;

import com.personal_dashboard.backend.model.ActivityLevel;
import com.personal_dashboard.backend.model.FitnessGoal;
import com.personal_dashboard.backend.model.UserAccount;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class HealthEngineService {

    public void calculateHealthMetrics(UserAccount user) {
        if (user == null) {
            return;
        }

        UserAccount.PhysicalMetrics metrics = user.getPhysicalMetrics();
        if (metrics == null || metrics.getWeight() == null || metrics.getHeight() == null || metrics.getAge() == null) {
            log.warn("Missing physical metrics for user calculations, skipping.");
            return;
        }

        double weight = metrics.getWeight();
        double height = metrics.getHeight();
        int age = metrics.getAge();
        String gender = metrics.getGender();

        if (weight <= 0 || height <= 0 || age <= 0) {
            log.warn("Invalid physical metrics (weight, height, or age <= 0), skipping.");
            return;
        }

        // 1. BMI
        double heightMeters = height / 100.0;
        double bmi = weight / (heightMeters * heightMeters);
        user.setBmi(roundToOnedp(bmi));

        // 2. BMR (Mifflin-St Jeor)
        boolean isMale = gender != null && (
                gender.equalsIgnoreCase("MALE") || 
                gender.equalsIgnoreCase("MEN") || 
                gender.equalsIgnoreCase("M") || 
                gender.equalsIgnoreCase("MAN")
        );
        
        double bmr;
        if (isMale) {
            bmr = 10.0 * weight + 6.25 * height - 5.0 * age + 5.0;
        } else {
            // Defaults or Female
            bmr = 10.0 * weight + 6.25 * height - 5.0 * age - 161.0;
        }
        user.setBmr(roundToOnedp(bmr));

        // 3. TDEE
        ActivityLevel activityLevel = user.getActivityLevel();
        if (activityLevel == null) {
            activityLevel = ActivityLevel.SEDENTARY; // fallback default
        }
        double multiplier = activityLevel.getMultiplier();
        double tdee = bmr * multiplier;
        user.setTdee(roundToOnedp(tdee));

        // 4. Daily Target Calorie Goal
        FitnessGoal fitnessGoal = user.getFitnessGoal();
        if (fitnessGoal == null) {
            fitnessGoal = FitnessGoal.MAINTAIN_WEIGHT; // fallback default
        }
        
        double adjustedCalories = tdee;
        if (fitnessGoal == FitnessGoal.LOSE_WEIGHT) {
            adjustedCalories = tdee - 500.0;
        } else if (fitnessGoal == FitnessGoal.GAIN_MUSCLE) {
            adjustedCalories = tdee + 300.0;
        }
        
        int targetCalories = (int) Math.round(adjustedCalories);
        if (targetCalories < 0) {
            targetCalories = 0;
        }

        // 5. Daily Macro Split
        // Protein: 2.0g per kg of body weight
        int calculatedProtein = (int) Math.round(2.0 * weight);
        if (calculatedProtein < 0) {
            calculatedProtein = 0;
        }

        // Fat: 25% of total adjusted Calorie Goal (1g Fat = 9 kcal)
        int calculatedFat = (int) Math.round((targetCalories * 0.25) / 9.0);
        if (calculatedFat < 0) {
            calculatedFat = 0;
        }

        // Carbohydrates: Remaining calories left over (1g Carb = 4 kcal)
        int proteinKcal = calculatedProtein * 4;
        int fatKcal = calculatedFat * 9;
        int remainingKcal = targetCalories - proteinKcal - fatKcal;
        int calculatedCarbs = (int) Math.round(remainingKcal / 4.0);
        if (calculatedCarbs < 0) {
            calculatedCarbs = 0;
        }

        UserAccount.DynamicTargets targets = UserAccount.DynamicTargets.builder()
                .calculatedCalories(targetCalories)
                .calculatedProtein(calculatedProtein)
                .calculatedCarbs(calculatedCarbs)
                .calculatedFat(calculatedFat)
                .build();

        user.setDynamicTargets(targets);

        // Keep legacy fields in sync for compatibility
        user.setTargetCalories(targetCalories);
        user.setTargetProtein(calculatedProtein);
        
        // Sync top-level compatibility fields
        user.setAge(age);
        user.setHeight(height);
        user.setWeight(weight);
    }

    private double roundToOnedp(double value) {
        return Math.round(value * 10.0) / 10.0;
    }
}
