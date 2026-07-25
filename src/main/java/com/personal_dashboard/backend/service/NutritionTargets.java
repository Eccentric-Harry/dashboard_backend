package com.personal_dashboard.backend.service;

import com.personal_dashboard.backend.model.UserAccount;

/**
 * The user's resolved daily nutrition targets.
 *
 * <p>Extracted so the Stage-2 prompt and the post-analysis budget arithmetic read the
 * same numbers from the same precedence chain (dynamic targets → explicit targets →
 * TDEE → clinical defaults). Previously the prompt asked the model to echo these values
 * back and do the percentage math itself, which cost output tokens for arithmetic Java
 * can do exactly.
 */
public record NutritionTargets(
        double calories,
        double proteinG,
        double carbsG,
        double fatG,
        double sodiumMg,
        double fiberG,
        double sugarMaxG) {

    // Clinical defaults, used when the profile carries nothing more specific.
    private static final double DEFAULT_CALORIES = 2000.0;
    private static final double DEFAULT_PROTEIN_G = 50.0;
    private static final double DEFAULT_CARBS_G = 250.0;
    private static final double DEFAULT_FAT_G = 65.0;
    private static final double DEFAULT_SODIUM_MG = 2300.0;
    private static final double DEFAULT_FIBER_G = 25.0;
    private static final double DEFAULT_SUGAR_MAX_G = 50.0;

    public static NutritionTargets from(UserAccount user) {
        if (user == null) {
            return new NutritionTargets(DEFAULT_CALORIES, DEFAULT_PROTEIN_G, DEFAULT_CARBS_G,
                    DEFAULT_FAT_G, DEFAULT_SODIUM_MG, DEFAULT_FIBER_G, DEFAULT_SUGAR_MAX_G);
        }

        double tdee = user.getTdee() != null ? user.getTdee() : DEFAULT_CALORIES;

        double calories = tdee;
        if (user.getDynamicTargets() != null && user.getDynamicTargets().getCalculatedCalories() != null) {
            calories = user.getDynamicTargets().getCalculatedCalories();
        } else if (user.getTargetCalories() != null) {
            calories = user.getTargetCalories();
        }

        double protein = DEFAULT_PROTEIN_G;
        if (user.getDynamicTargets() != null && user.getDynamicTargets().getCalculatedProtein() != null) {
            protein = user.getDynamicTargets().getCalculatedProtein();
        } else if (user.getTargetProtein() != null) {
            protein = user.getTargetProtein();
        }

        double carbs = DEFAULT_CARBS_G;
        if (user.getDynamicTargets() != null && user.getDynamicTargets().getCalculatedCarbs() != null) {
            carbs = user.getDynamicTargets().getCalculatedCarbs();
        }

        double fat = DEFAULT_FAT_G;
        if (user.getDynamicTargets() != null && user.getDynamicTargets().getCalculatedFat() != null) {
            fat = user.getDynamicTargets().getCalculatedFat();
        }

        // Not modelled on UserAccount today — clinical defaults stand in.
        return new NutritionTargets(calories, protein, carbs, fat,
                DEFAULT_SODIUM_MG, DEFAULT_FIBER_G, DEFAULT_SUGAR_MAX_G);
    }

    /** Percentage of the daily target this meal represents, clamped to a sane display range. */
    public static double percentOf(double amount, double target) {
        if (target <= 0) return 0.0;
        return Math.round((amount / target) * 1000.0) / 10.0;
    }
}
