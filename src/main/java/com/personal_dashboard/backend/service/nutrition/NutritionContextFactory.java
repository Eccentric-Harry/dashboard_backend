package com.personal_dashboard.backend.service.nutrition;

import com.personal_dashboard.backend.model.DailyFoodLog;
import com.personal_dashboard.backend.model.DailyTotals;
import com.personal_dashboard.backend.model.UserAccount;
import com.personal_dashboard.backend.service.NutritionTargets;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Assembles the {@link NutritionContext} for a request: the user's profile, their resolved
 * daily targets, and what they have <em>actually</em> eaten so far today.
 *
 * <p>Target resolution deliberately delegates to {@link NutritionTargets} rather than
 * repeating its precedence chain, so the prompt, the budget arithmetic and the rule engine
 * can never disagree about what the user's goals are.</p>
 */
@Component
public class NutritionContextFactory {

    // AHA sodium guidance: 1500 mg/day where blood pressure or renal function is a concern.
    private static final double SODIUM_TARGET_RESTRICTED_MG = 1500.0;
    // AHA added-sugar guidance, which is sex-specific.
    private static final double ADDED_SUGAR_MAX_FEMALE_G = 25.0;
    private static final double ADDED_SUGAR_MAX_MALE_G = 36.0;

    /**
     * @param user     the user's profile; may be null for an unconfigured account
     * @param todayLog today's food log; may be null before the first meal of the day
     */
    public NutritionContext build(UserAccount user, DailyFoodLog todayLog) {
        NutritionTargets targets = NutritionTargets.from(user);
        DailyTotals consumed = todayLog != null ? todayLog.getDailyTotals() : null;

        List<String> conditions = (user != null && user.getMedicalConditions() != null)
                ? user.getMedicalConditions() : List.of();

        UserAccount.PhysicalMetrics pm = user != null ? user.getPhysicalMetrics() : null;
        String sex = pm != null && pm.getGender() != null ? pm.getGender() : "Not specified";

        // Sodium and added-sugar ceilings are not stored on the profile. Deriving them from
        // guidance beats applying one flat number to everyone: 2300 mg is the general
        // ceiling, but 1500 mg is the target once hypertension or CKD is in play.
        boolean sodiumRestricted = conditions.stream()
                .filter(Objects::nonNull)
                .map(c -> c.toLowerCase(Locale.ROOT))
                .anyMatch(c -> c.contains("hypertens") || c.contains("blood pressure")
                        || c.contains("kidney") || c.contains("renal") || c.contains("ckd"));

        return NutritionContext.builder()
                .displayName(user != null && user.getDisplayName() != null ? user.getDisplayName() : "User")
                .age(firstNonNull(pm != null ? pm.getAge() : null, user != null ? user.getAge() : null, 0))
                .biologicalSex(sex)
                .heightCm(firstNonNull(pm != null ? pm.getHeight() : null,
                        user != null ? user.getHeight() : null, 0.0))
                .weightKg(firstNonNull(pm != null ? pm.getWeight() : null,
                        user != null ? user.getWeight() : null, 0.0))
                .bmi(user != null && user.getBmi() != null ? user.getBmi() : 0.0)
                .activityLevel(user != null && user.getActivityLevel() != null
                        ? user.getActivityLevel().name() : "Unknown")
                .tdee(user != null && user.getTdee() != null ? user.getTdee() : targets.calories())
                .primaryGoal(user != null && user.getFitnessGoal() != null
                        ? user.getFitnessGoal().name() : "Not specified")
                .goalCalories(targets.calories())
                .goalProteinG(targets.proteinG())
                .goalCarbsG(targets.carbsG())
                .goalFatG(targets.fatG())
                .goalSodiumMg(sodiumRestricted ? SODIUM_TARGET_RESTRICTED_MG : targets.sodiumMg())
                .goalFiberG(targets.fiberG())
                .goalAddedSugarMaxG(sex.toLowerCase(Locale.ROOT).startsWith("f")
                        ? ADDED_SUGAR_MAX_FEMALE_G : ADDED_SUGAR_MAX_MALE_G)
                .consumedCalories(value(consumed != null ? consumed.getTotalCalories() : null))
                .consumedProteinG(value(consumed != null ? consumed.getTotalProteinGrams() : null))
                .consumedCarbsG(value(consumed != null ? consumed.getTotalCarbsGrams() : null))
                .consumedFatG(value(consumed != null ? consumed.getTotalFatGrams() : null))
                .consumedSodiumMg(value(consumed != null ? consumed.getTotalSodiumMg() : null))
                .consumedFiberG(value(consumed != null ? consumed.getTotalFiberGrams() : null))
                .medicalConditions(conditions)
                .build();
    }

    private static double value(Number n) { return n != null ? n.doubleValue() : 0.0; }

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
