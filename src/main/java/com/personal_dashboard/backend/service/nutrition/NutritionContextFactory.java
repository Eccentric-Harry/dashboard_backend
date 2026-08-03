package com.personal_dashboard.backend.service.nutrition;

import com.personal_dashboard.backend.model.DailyFoodLog;
import com.personal_dashboard.backend.model.DailyTotals;
import com.personal_dashboard.backend.model.UserAccount;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Assembles the {@link NutritionContext} for a request from the user's profile and the
 * <em>actual</em> daily food log.
 *
 * <p>The previous pipeline told the model that consumed-today figures came from the daily log
 * while passing hard-coded zeros, so every meal was judged against a full untouched budget and
 * the resulting "remaining budget" guidance was fiction. Reading the real totals is what makes
 * that section mean anything.</p>
 */
@Component
public class NutritionContextFactory {

    // AHA sodium guidance: 1500 mg/day ideal, 2300 mg/day acceptable ceiling.
    private static final double SODIUM_TARGET_DEFAULT_MG = 2300.0;
    private static final double SODIUM_TARGET_RESTRICTED_MG = 1500.0;
    // AHA added-sugar guidance.
    private static final double ADDED_SUGAR_MAX_FEMALE_G = 25.0;
    private static final double ADDED_SUGAR_MAX_MALE_G = 36.0;
    private static final double FIBER_TARGET_G = 25.0;

    /**
     * @param user     the user's profile, may be null for an unconfigured account
     * @param todayLog today's food log, may be null before the first meal of the day
     */
    public NutritionContext build(UserAccount user, DailyFoodLog todayLog) {
        NutritionContext.NutritionContextBuilder b = NutritionContext.builder();

        if (user == null) {
            return b.displayName("User")
                    .biologicalSex("Not specified")
                    .activityLevel("Unknown")
                    .primaryGoal("Not specified")
                    .tdee(2000).goalCalories(2000).goalProteinG(50)
                    .goalCarbsG(250).goalFatG(65)
                    .goalSodiumMg(SODIUM_TARGET_DEFAULT_MG)
                    .goalFiberG(FIBER_TARGET_G)
                    .goalAddedSugarMaxG(ADDED_SUGAR_MAX_MALE_G)
                    .medicalConditions(List.of())
                    .build();
        }

        UserAccount.PhysicalMetrics pm = user.getPhysicalMetrics();

        int age = firstNonNull(pm != null ? pm.getAge() : null, user.getAge(), 0);
        double height = firstNonNull(pm != null ? pm.getHeight() : null, user.getHeight(), 0.0);
        double weight = firstNonNull(pm != null ? pm.getWeight() : null, user.getWeight(), 0.0);
        String sex = pm != null && pm.getGender() != null ? pm.getGender() : "Not specified";

        double tdee = user.getTdee() != null ? user.getTdee() : 2000.0;
        UserAccount.DynamicTargets dt = user.getDynamicTargets();

        double goalCalories = dt != null && dt.getCalculatedCalories() != null
                ? dt.getCalculatedCalories()
                : (user.getTargetCalories() != null ? user.getTargetCalories() : tdee);
        double goalProtein = dt != null && dt.getCalculatedProtein() != null
                ? dt.getCalculatedProtein()
                : (user.getTargetProtein() != null ? user.getTargetProtein() : 50.0);
        double goalCarbs = dt != null && dt.getCalculatedCarbs() != null
                ? dt.getCalculatedCarbs() : 250.0;
        double goalFat = dt != null && dt.getCalculatedFat() != null
                ? dt.getCalculatedFat() : 65.0;

        List<String> conditions = user.getMedicalConditions() != null
                ? user.getMedicalConditions() : List.of();

        // Sodium and sugar targets are not stored on the profile, so derive them from
        // guidelines rather than applying one flat number to everyone.
        boolean sodiumRestricted = conditions.stream()
                .filter(java.util.Objects::nonNull)
                .map(String::toLowerCase)
                .anyMatch(c -> c.contains("hypertens") || c.contains("blood pressure")
                        || c.contains("kidney") || c.contains("renal") || c.contains("ckd"));
        double sodiumTarget = sodiumRestricted ? SODIUM_TARGET_RESTRICTED_MG : SODIUM_TARGET_DEFAULT_MG;
        double sugarMax = sex != null && sex.toLowerCase().startsWith("f")
                ? ADDED_SUGAR_MAX_FEMALE_G : ADDED_SUGAR_MAX_MALE_G;

        DailyTotals consumed = todayLog != null ? todayLog.getDailyTotals() : null;

        return b.displayName(user.getDisplayName() != null ? user.getDisplayName() : "User")
                .age(age)
                .biologicalSex(sex)
                .heightCm(height)
                .weightKg(weight)
                .bmi(user.getBmi() != null ? user.getBmi() : 0.0)
                .activityLevel(user.getActivityLevel() != null ? user.getActivityLevel().name() : "Unknown")
                .tdee(tdee)
                .primaryGoal(user.getFitnessGoal() != null ? user.getFitnessGoal().name() : "Not specified")
                .goalCalories(goalCalories)
                .goalProteinG(goalProtein)
                .goalCarbsG(goalCarbs)
                .goalFatG(goalFat)
                .goalSodiumMg(sodiumTarget)
                .goalFiberG(FIBER_TARGET_G)
                .goalAddedSugarMaxG(sugarMax)
                .consumedCalories(consumed != null && consumed.getTotalCalories() != null
                        ? consumed.getTotalCalories() : 0.0)
                .consumedProteinG(consumed != null && consumed.getTotalProteinGrams() != null
                        ? consumed.getTotalProteinGrams() : 0.0)
                .consumedCarbsG(consumed != null && consumed.getTotalCarbsGrams() != null
                        ? consumed.getTotalCarbsGrams() : 0.0)
                .consumedFatG(consumed != null && consumed.getTotalFatGrams() != null
                        ? consumed.getTotalFatGrams() : 0.0)
                .consumedSodiumMg(consumed != null && consumed.getTotalSodiumMg() != null
                        ? consumed.getTotalSodiumMg() : 0.0)
                .consumedFiberG(consumed != null && consumed.getTotalFiberGrams() != null
                        ? consumed.getTotalFiberGrams() : 0.0)
                .medicalConditions(conditions)
                .build();
    }

    private static int firstNonNull(Integer a, Integer b, int fallback) {
        if (a != null) return a;
        if (b != null) return b;
        return fallback;
    }

    private static double firstNonNull(Double a, Double b, double fallback) {
        if (a != null) return a;
        if (b != null) return b;
        return fallback;
    }
}
