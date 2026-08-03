package com.personal_dashboard.backend.service.nutrition;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Nutrient composition of a food, expressed per 100 g of edible portion.
 *
 * <p>This is the unit in which USDA FoodData Central publishes its data, and the unit
 * in which every value in {@code nutrition/usda-core.json} is stored. Keeping a single
 * canonical basis is what makes the downstream arithmetic exact: the only operation ever
 * applied is a linear scale by {@code grams / 100}.</p>
 *
 * <p>Nothing in this class is estimated by a language model. Values come from the embedded
 * USDA table or the live FoodData Central API.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class NutrientProfile {

    /** Energy in kilocalories per 100 g. */
    @Builder.Default private double kcal = 0.0;
    /** Protein in grams per 100 g. */
    @Builder.Default private double protein = 0.0;
    /** Total carbohydrate in grams per 100 g. */
    @Builder.Default private double carbs = 0.0;
    /** Total fat in grams per 100 g. */
    @Builder.Default private double fat = 0.0;
    /** Saturated fat in grams per 100 g. */
    @Builder.Default private double satFat = 0.0;
    /** Dietary fibre in grams per 100 g. */
    @Builder.Default private double fiber = 0.0;
    /** Total sugars in grams per 100 g. */
    @Builder.Default private double sugar = 0.0;
    /** Sodium in milligrams per 100 g. */
    @Builder.Default private double sodium = 0.0;
    /** Potassium in milligrams per 100 g. */
    @Builder.Default private double potassium = 0.0;
    /** Cholesterol in milligrams per 100 g. */
    @Builder.Default private double cholesterol = 0.0;

    /**
     * Scales this per-100 g profile to an arbitrary gram weight.
     *
     * @param grams edible weight in grams; negative values are clamped to zero
     * @return a new profile whose fields are absolute amounts for {@code grams}
     */
    public NutrientProfile scaleTo(double grams) {
        double f = Math.max(grams, 0.0) / 100.0;
        return NutrientProfile.builder()
                .kcal(kcal * f)
                .protein(protein * f)
                .carbs(carbs * f)
                .fat(fat * f)
                .satFat(satFat * f)
                .fiber(fiber * f)
                .sugar(sugar * f)
                .sodium(sodium * f)
                .potassium(potassium * f)
                .cholesterol(cholesterol * f)
                .build();
    }

    /**
     * Adds another absolute-amount profile to this one, returning the sum.
     */
    public NutrientProfile plus(NutrientProfile o) {
        if (o == null) return this;
        return NutrientProfile.builder()
                .kcal(kcal + o.kcal)
                .protein(protein + o.protein)
                .carbs(carbs + o.carbs)
                .fat(fat + o.fat)
                .satFat(satFat + o.satFat)
                .fiber(fiber + o.fiber)
                .sugar(sugar + o.sugar)
                .sodium(sodium + o.sodium)
                .potassium(potassium + o.potassium)
                .cholesterol(cholesterol + o.cholesterol)
                .build();
    }

    /**
     * Energy recomputed from macronutrients using Atwater factors
     * (protein 4 kcal/g, carbohydrate 4 kcal/g, fat 9 kcal/g).
     *
     * <p>USDA publishes measured energy values that may differ slightly from the Atwater
     * sum because of fibre and sugar-alcohol handling. We report the USDA value as
     * authoritative and use this only to expose the delta for transparency.</p>
     */
    public double atwaterKcal() {
        return protein * 4.0 + carbs * 4.0 + fat * 9.0;
    }

    /** Carbohydrate net of dietary fibre, floored at zero. Used for glycaemic load. */
    public double netCarbs() {
        return Math.max(carbs - fiber, 0.0);
    }
}
