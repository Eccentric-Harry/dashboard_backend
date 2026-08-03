package com.personal_dashboard.backend.service.nutrition;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Locale;

/**
 * The user-specific inputs a meal is judged against: daily targets, what has already been
 * eaten today, and active medical conditions.
 *
 * <p>Assembled once by the controller from the user's profile and the real daily food log.
 * The previous implementation hard-coded every consumed figure to zero while telling the model
 * they came from the log, so "remaining budget" was always a full untouched day.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NutritionContext {

    private String displayName;
    private int age;
    private String biologicalSex;
    private double heightCm;
    private double weightKg;
    private double bmi;
    private String activityLevel;
    private double tdee;
    private String primaryGoal;

    // Daily targets
    private double goalCalories;
    private double goalProteinG;
    private double goalCarbsG;
    private double goalFatG;
    private double goalSodiumMg;
    private double goalFiberG;
    private double goalAddedSugarMaxG;

    // Actually consumed today, before this meal
    private double consumedCalories;
    private double consumedProteinG;
    private double consumedCarbsG;
    private double consumedFatG;
    private double consumedSodiumMg;
    private double consumedFiberG;

    @Builder.Default
    private List<String> medicalConditions = List.of();

    // ─── Condition predicates ──────────────────────────────────────────────

    private boolean has(String... needles) {
        if (medicalConditions == null) return false;
        return medicalConditions.stream()
                .filter(java.util.Objects::nonNull)
                .map(c -> c.toLowerCase(Locale.ROOT))
                .anyMatch(c -> {
                    for (String n : needles) {
                        if (c.contains(n)) return true;
                    }
                    return false;
                });
    }

    public boolean hasAcne()         { return has("acne"); }
    public boolean hasDiabetes()     { return has("diabet", "insulin resist", "prediabet"); }
    public boolean hasHypertension() { return has("hypertens", "blood pressure"); }
    public boolean hasPcos()         { return has("pcos", "polycystic"); }
    public boolean hasIbs()          { return has("ibs", "irritable bowel"); }
    public boolean hasKidneyDisease(){ return has("kidney", "renal", "ckd"); }

    public double remainingCalories() { return goalCalories - consumedCalories; }
    public double remainingProtein()  { return goalProteinG - consumedProteinG; }
    public double remainingCarbs()    { return goalCarbsG - consumedCarbsG; }
    public double remainingFat()      { return goalFatG - consumedFatG; }
    public double remainingSodium()   { return goalSodiumMg - consumedSodiumMg; }
}
