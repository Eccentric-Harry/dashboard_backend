package com.personal_dashboard.backend.service.nutrition;

import com.personal_dashboard.backend.dto.GeminiAnalysisResult;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Applies the guideline thresholds that were previously written out as prose instructions
 * and re-derived by the language model on every request.
 *
 * <p>Rules like "flag if this meal contributes more than 600 mg sodium" or
 * "GL above 20 is a high post-prandial excursion risk" are comparisons, not reasoning. Running
 * them in Java makes them deterministic, auditable, instantly changeable, and free — while the
 * model keeps the part it is genuinely better at: explaining the finding and suggesting swaps.</p>
 *
 * <p>Thresholds and their sources:</p>
 * <ul>
 *   <li>Sodium — AHA: 1500 mg/day ideal, 2300 mg/day acceptable.</li>
 *   <li>Carbohydrate per meal — ADA Standards of Care: 45–60 g single-meal guidance.</li>
 *   <li>Glycaemic load bands — Foster-Powell / Brand-Miller international tables.</li>
 *   <li>Added sugar — AHA: 25 g/day (women), 36 g/day (men).</li>
 *   <li>Saturated fat — AHA: under 7% of daily energy.</li>
 *   <li>Protein in CKD — KDOQI: 0.6–0.8 g/kg body weight per day.</li>
 * </ul>
 */
@Component
public class ClinicalRuleEngine {

    private static final double SODIUM_MEAL_HIGH_MG = 600.0;
    private static final double SODIUM_MEAL_CKD_MG = 500.0;
    private static final double ADA_MEAL_CARB_CEILING_G = 60.0;
    private static final double GL_HIGH = 20.0;
    private static final double GL_MEDIUM = 10.0;
    private static final double IBS_FAT_TRIGGER_G = 20.0;
    private static final double CKD_POTASSIUM_MG = 500.0;
    private static final double NEAR_LIMIT_FRACTION = 0.80;

    /**
     * Evaluates every applicable rule against a computed meal.
     *
     * @return flags ordered most severe first
     */
    public List<GeminiAnalysisResult.ClinicalFlag> evaluate(ComputedNutrition n, NutritionContext ctx) {
        List<GeminiAnalysisResult.ClinicalFlag> flags = new ArrayList<>();
        NutrientProfile t = n.getTotals();
        double netCarbs = t.netCarbs();

        // ── Glycaemic load — applies to everyone, escalated by condition ──
        if (n.getGlycaemicLoad() >= GL_HIGH) {
            String condition = ctx.hasDiabetes() ? "Diabetes / Insulin Resistance"
                    : ctx.hasPcos() ? "PCOS"
                    : ctx.hasAcne() ? "Acne" : "General Population";
            flags.add(flag("HIGH", "GLYCAEMIC", condition,
                    "High Glycaemic Load — Insulin Spike Risk",
                    ctx.hasDiabetes() ? "ADA Standards of Care 2024, Section 5"
                            : "Foster-Powell & Brand-Miller, International Tables of GI/GL",
                    "Rapidly digested carbohydrate raises blood glucose quickly, driving a large "
                            + "insulin response and, downstream, elevated IGF-1 and androgen activity.",
                    topGiOffenders(n),
                    String.format("Meal glycaemic load is %.1f, above the high-GL threshold of %.0f.",
                            n.getGlycaemicLoad(), GL_HIGH),
                    ctx.hasDiabetes() || ctx.hasPcos() ? "Avoid" : "Reduce"));
        } else if (n.getGlycaemicLoad() >= GL_MEDIUM && (ctx.hasDiabetes() || ctx.hasPcos())) {
            flags.add(flag("MODERATE", "GLYCAEMIC",
                    ctx.hasDiabetes() ? "Diabetes / Insulin Resistance" : "PCOS",
                    "Moderate Glycaemic Load",
                    "ADA Standards of Care 2024",
                    "Moderate post-prandial glucose rise; manageable but worth pairing with protein "
                            + "or fibre to blunt the curve.",
                    topGiOffenders(n),
                    String.format("Meal glycaemic load is %.1f (medium band 10–19).", n.getGlycaemicLoad()),
                    "Monitor"));
        }

        // ── Carbohydrate load per meal (ADA) ──
        if (ctx.hasDiabetes() && netCarbs > ADA_MEAL_CARB_CEILING_G) {
            flags.add(flag("HIGH", "GLYCAEMIC", "Diabetes / Insulin Resistance",
                    "Single-Meal Carbohydrate Above ADA Guidance",
                    "ADA Standards of Care 2024",
                    "Carbohydrate load in one sitting exceeds what a typical insulin response "
                            + "handles without a pronounced glucose excursion.",
                    List.of(),
                    String.format("%.1f g net carbohydrate against ADA single-meal guidance of 45–60 g.", netCarbs),
                    "Reduce"));
        }

        // ── Sodium ──
        double sodiumCeiling = ctx.hasKidneyDisease() ? SODIUM_MEAL_CKD_MG : SODIUM_MEAL_HIGH_MG;
        if (t.getSodium() > sodiumCeiling && (ctx.hasHypertension() || ctx.hasKidneyDisease())) {
            flags.add(flag("HIGH", "CARDIOVASCULAR",
                    ctx.hasKidneyDisease() ? "Chronic Kidney Disease" : "Hypertension",
                    "High Sodium Load",
                    ctx.hasKidneyDisease() ? "KDOQI / NKF dietary guidelines"
                            : "AHA dietary sodium guidelines",
                    "Sodium increases extracellular fluid volume, raising blood pressure and, in "
                            + "reduced renal function, adding to filtration burden.",
                    topSodiumContributors(n),
                    String.format("%.0f mg sodium in this meal — %.0f%% of the %.0f mg daily target.",
                            t.getSodium(), pct(t.getSodium(), ctx.getGoalSodiumMg()), ctx.getGoalSodiumMg()),
                    "Avoid"));
        } else if (t.getSodium() > SODIUM_MEAL_HIGH_MG) {
            flags.add(flag("MODERATE", "CARDIOVASCULAR", "General Population",
                    "Elevated Sodium",
                    "WHO Global Action Plan on NCDs",
                    "Habitually high sodium intake raises blood pressure over time even without "
                            + "a current hypertension diagnosis.",
                    topSodiumContributors(n),
                    String.format("%.0f mg sodium — %.0f%% of the %.0f mg daily target.",
                            t.getSodium(), pct(t.getSodium(), ctx.getGoalSodiumMg()), ctx.getGoalSodiumMg()),
                    "Monitor"));
        }

        // ── Added sugar (AHA) ──
        if (t.getSugar() > ctx.getGoalAddedSugarMaxG()) {
            flags.add(flag("MODERATE", "MACRO_IMBALANCE", "General Population",
                    "Sugar Above Daily Allowance",
                    "AHA added-sugar guidance",
                    "Free sugars deliver energy with no micronutrient return and drive the same "
                            + "insulin response as refined starch.",
                    List.of(),
                    String.format("%.1f g total sugars — %.0f%% of the %.0f g daily ceiling.",
                            t.getSugar(), pct(t.getSugar(), ctx.getGoalAddedSugarMaxG()), ctx.getGoalAddedSugarMaxG()),
                    "Reduce"));
        }

        // ── Saturated fat (AHA: <7% of daily energy) ──
        double satFatCeiling = ctx.getGoalCalories() * 0.07 / 9.0;
        if (satFatCeiling > 0 && t.getSatFat() > satFatCeiling) {
            flags.add(flag("MODERATE", "CARDIOVASCULAR", "General Population",
                    "Saturated Fat Above AHA Ceiling",
                    "AHA: saturated fat below 7% of daily energy",
                    "Saturated fat raises LDL particle concentration, a causal driver of "
                            + "atherosclerotic cardiovascular disease.",
                    List.of(),
                    String.format("%.1f g saturated fat against a daily ceiling of %.1f g.",
                            t.getSatFat(), satFatCeiling),
                    "Reduce"));
        }

        // ── IBS fat trigger (NICE 2017) ──
        if (ctx.hasIbs() && t.getFat() > IBS_FAT_TRIGGER_G) {
            flags.add(flag("MODERATE", "DIGESTIVE", "IBS",
                    "High Fat Load — Possible IBS Trigger",
                    "NICE IBS dietary guidelines (2017)",
                    "Fat stimulates the gastrocolic reflex and slows gastric emptying, which can "
                            + "provoke cramping and urgency in IBS.",
                    List.of(),
                    String.format("%.1f g fat, above the %.0f g per-meal trigger threshold.",
                            t.getFat(), IBS_FAT_TRIGGER_G),
                    "Monitor"));
        }

        // ── CKD protein and potassium (KDOQI) ──
        if (ctx.hasKidneyDisease() && ctx.getWeightKg() > 0) {
            double perKg = t.getProtein() / ctx.getWeightKg();
            if (perKg > 0.6) {
                flags.add(flag("HIGH", "RENAL", "Chronic Kidney Disease",
                        "Protein Load Above KDOQI Single-Sitting Guidance",
                        "KDOQI / NKF dietary guidelines",
                        "Protein catabolism raises blood urea nitrogen, increasing filtration "
                                + "workload on already-reduced renal capacity.",
                        List.of(),
                        String.format("%.1f g protein = %.2f g/kg body weight in one sitting.",
                                t.getProtein(), perKg),
                        "Consult Physician"));
            }
            if (t.getPotassium() > CKD_POTASSIUM_MG) {
                flags.add(flag("HIGH", "RENAL", "Chronic Kidney Disease",
                        "High Potassium Load",
                        "KDOQI / NKF dietary guidelines",
                        "Impaired potassium excretion risks hyperkalaemia, which affects cardiac "
                                + "conduction.",
                        List.of(),
                        String.format("%.0f mg potassium, above the %.0f mg per-meal caution threshold.",
                                t.getPotassium(), CKD_POTASSIUM_MG),
                        "Consult Physician"));
            }
        }

        // ── Budget overrun ──
        if (t.getKcal() > ctx.remainingCalories() && ctx.remainingCalories() > 0) {
            flags.add(flag("MODERATE", "MACRO_IMBALANCE", "General Population",
                    "Meal Exceeds Remaining Daily Energy Budget",
                    "Derived from the user's calculated TDEE and goal",
                    "Sustained intake above expenditure produces a positive energy balance and "
                            + "fat gain regardless of food quality.",
                    List.of(),
                    String.format("%.0f kcal against %.0f kcal remaining today.",
                            t.getKcal(), ctx.remainingCalories()),
                    "Monitor"));
        }

        // ── Data quality is a clinical concern, not a footnote ──
        if (!n.getUnresolvedIngredients().isEmpty()) {
            // Severity keyed on unmatched MASS, not item count. One unmatched scoop of whey
            // is a bigger hit to the protein total than three unmatched garnishes, and
            // energy-weighted confidence would barely register either.
            double missingShare = 1.0 - n.getMassCoverage();
            String severity = missingShare >= 0.25 ? "HIGH"
                    : missingShare >= 0.10 ? "MODERATE" : "LOW";
            flags.add(flag(severity, "GENERAL", "Data Quality",
                    "Some Ingredients Not Matched to a Nutrient Record",
                    "Internal data-quality check",
                    "These items are shown but contribute no nutrients to the totals, so every "
                            + "figure below understates the meal. Protein is usually the worst hit, "
                            + "because protein-dense foods carry little energy and so slip past an "
                            + "energy-based confidence check.",
                    n.getUnresolvedIngredients(),
                    String.format("%d of %d ingredients unmatched, %.0f g (%.0f%% of meal weight).",
                            n.getUnresolvedIngredients().size(), n.getItems().size(),
                            n.getUnresolvedGrams(), missingShare * 100),
                    missingShare >= 0.10 ? "Reduce" : "Monitor"));
        }

        // ── Ingredient decomposition vs the model's holistic read of the dish ──
        // Summing a visible ingredient list systematically misses what the camera cannot
        // see: oil absorbed during frying, sugar dissolved in chai, ghee brushed on a roti.
        // The model's whole-dish estimate is a poor number to report but a good detector,
        // because it draws on knowing what this dish usually weighs in at.
        if (n.getPlausibilityRatio() != null) {
            double ratio = n.getPlausibilityRatio();
            if (ratio < 0.65) {
                flags.add(flag("MODERATE", "GENERAL", "Data Quality",
                        "Ingredient Total Well Below the Expected Range for This Dish",
                        "Internal cross-check against the vision model's whole-dish estimate",
                        "Ingredient-level decomposition tends to miss what is not visible — "
                                + "absorbed cooking oil, dissolved sugar, ghee on bread. The figures "
                                + "below are more likely to understate this meal than overstate it.",
                        List.of(),
                        String.format("Ingredients sum to %.0f kcal against a whole-dish estimate "
                                + "of %.0f kcal (%.0f%%).",
                                n.getTotals().getKcal(), n.getDishLevelEstimateKcal(), ratio * 100),
                        "Monitor"));
            } else if (ratio > 1.5) {
                flags.add(flag("LOW", "GENERAL", "Data Quality",
                        "Ingredient Total Above the Expected Range for This Dish",
                        "Internal cross-check against the vision model's whole-dish estimate",
                        "Portions may have been over-estimated, or an ingredient double-counted "
                                + "across multiple photographs of the same plate.",
                        List.of(),
                        String.format("Ingredients sum to %.0f kcal against a whole-dish estimate "
                                + "of %.0f kcal (%.0f%%).",
                                n.getTotals().getKcal(), n.getDishLevelEstimateKcal(), ratio * 100),
                        "Monitor"));
            }
        }

        flags.sort((a, b) -> rank(b.getSeverity()) - rank(a.getSeverity()));
        for (int i = 0; i < flags.size(); i++) {
            flags.get(i).setFlagId(String.format("FLAG_%03d", i + 1));
        }
        return flags;
    }

    /** Builds the budget block from real consumed totals. */
    public GeminiAnalysisResult.DailyBudgetAnalysis budget(ComputedNutrition n, NutritionContext ctx) {
        NutrientProfile t = n.getTotals();

        var before = GeminiAnalysisResult.BudgetValues.builder()
                .caloriesKcal(round(ctx.remainingCalories()))
                .proteinG(round(ctx.remainingProtein()))
                .carbsG(round(ctx.remainingCarbs()))
                .fatG(round(ctx.remainingFat()))
                .sodiumMg(round(ctx.remainingSodium()))
                .build();

        var after = GeminiAnalysisResult.BudgetValues.builder()
                .caloriesKcal(round(ctx.remainingCalories() - t.getKcal()))
                .proteinG(round(ctx.remainingProtein() - t.getProtein()))
                .carbsG(round(ctx.remainingCarbs() - t.getCarbs()))
                .fatG(round(ctx.remainingFat() - t.getFat()))
                .sodiumMg(round(ctx.remainingSodium() - t.getSodium()))
                .build();

        var status = GeminiAnalysisResult.BudgetStatus.builder()
                .calories(status(ctx.getConsumedCalories() + t.getKcal(), ctx.getGoalCalories()))
                .protein(status(ctx.getConsumedProteinG() + t.getProtein(), ctx.getGoalProteinG()))
                .carbs(status(ctx.getConsumedCarbsG() + t.getCarbs(), ctx.getGoalCarbsG()))
                .fat(status(ctx.getConsumedFatG() + t.getFat(), ctx.getGoalFatG()))
                .sodium(status(ctx.getConsumedSodiumMg() + t.getSodium(), ctx.getGoalSodiumMg()))
                .build();

        var percentages = GeminiAnalysisResult.DailyGoalPercentages.builder()
                .caloriesPct(round(pct(t.getKcal(), ctx.getGoalCalories())))
                .proteinPct(round(pct(t.getProtein(), ctx.getGoalProteinG())))
                .carbsPct(round(pct(t.getCarbs(), ctx.getGoalCarbsG())))
                .fatPct(round(pct(t.getFat(), ctx.getGoalFatG())))
                .sodiumPct(round(pct(t.getSodium(), ctx.getGoalSodiumMg())))
                .build();

        return GeminiAnalysisResult.DailyBudgetAnalysis.builder()
                .remainingBudgetBeforeThisMeal(before)
                .remainingBudgetAfterThisMeal(after)
                .budgetStatus(status)
                .percentageOfDailyGoalsThisMeal(percentages)
                .build();
    }

    /**
     * Composite meal score. Deterministic so the same meal always grades identically —
     * previously the model re-invented a 0–100 score each time and could grade the same
     * plate differently on consecutive runs.
     */
    public GeminiAnalysisResult.MealScore score(ComputedNutrition n,
                                                NutritionContext ctx,
                                                List<GeminiAnalysisResult.ClinicalFlag> flags) {
        NutrientProfile t = n.getTotals();
        double kcal = Math.max(t.getKcal(), 1.0);

        int glycaemic = clamp((int) Math.round(100 - n.getGlycaemicLoad() * 2.5));

        // Macro balance: protein share, fat share, fibre density.
        double proteinPctEnergy = t.getProtein() * 4.0 / kcal * 100;
        double fatPctEnergy = t.getFat() * 9.0 / kcal * 100;
        double fibrePer1000 = t.getFiber() / kcal * 1000;
        int macro = 100;
        if (proteinPctEnergy < 15) macro -= 20;
        else if (proteinPctEnergy < 20) macro -= 8;
        if (fatPctEnergy > 40) macro -= 15;
        else if (fatPctEnergy > 35) macro -= 6;
        if (fibrePer1000 < 8) macro -= 15;
        else if (fibrePer1000 < 14) macro -= 6;
        macro = clamp(macro);

        // Micronutrient density proxy: fibre and potassium per 1000 kcal.
        double potassiumPer1000 = t.getPotassium() / kcal * 1000;
        int micro = clamp((int) Math.round(
                Math.min(fibrePer1000 / 14.0, 1.0) * 50 + Math.min(potassiumPer1000 / 1800.0, 1.0) * 50));

        int conditionSafety = 100;
        for (var f : flags) {
            conditionSafety -= switch (f.getSeverity()) {
                case "CRITICAL" -> 35;
                case "HIGH" -> 25;
                case "MODERATE" -> 10;
                default -> 3;
            };
        }
        conditionSafety = clamp(conditionSafety);

        int overall = clamp((int) Math.round(
                macro * 0.30 + glycaemic * 0.25 + micro * 0.20 + conditionSafety * 0.25));

        return GeminiAnalysisResult.MealScore.builder()
                .overallScore(overall)
                .macroBalanceScore(macro)
                .glycaemicScore(glycaemic)
                .micronutrientDensityScore(micro)
                .conditionSafetyScore(conditionSafety)
                .letterGrade(grade(overall))
                .scoreRationale(String.format(
                        "Macro balance %d/100 (protein %.0f%% of energy, fat %.0f%%, fibre %.1f g/1000 kcal), "
                                + "glycaemic %d/100 (GL %.1f), micronutrient density %d/100, "
                                + "condition safety %d/100 across %d flag(s).",
                        macro, proteinPctEnergy, fatPctEnergy, fibrePer1000,
                        glycaemic, n.getGlycaemicLoad(), micro, conditionSafety, flags.size()))
                .build();
    }

    // ─── Helpers ───────────────────────────────────────────────────────────

    private static GeminiAnalysisResult.ClinicalFlag flag(
            String severity, String category, String conditionLink, String title,
            String evidence, String mechanism, List<String> ingredients,
            String quantifiedRisk, String urgency) {
        return GeminiAnalysisResult.ClinicalFlag.builder()
                .severity(severity)
                .category(category)
                .conditionLink(conditionLink)
                .title(title)
                .evidenceBasis(evidence)
                .mechanisticPathway(mechanism)
                .affectedIngredients(ingredients)
                .quantifiedRisk(quantifiedRisk)
                .urgency(urgency)
                .build();
    }

    private static List<String> topGiOffenders(ComputedNutrition n) {
        return n.getItems().stream()
                .filter(i -> i.getGlycaemicLoadContribution() != null && i.getGlycaemicLoadContribution() > 1)
                .sorted((a, b) -> Double.compare(b.getGlycaemicLoadContribution(), a.getGlycaemicLoadContribution()))
                .limit(3)
                .map(i -> i.getCommonName() != null ? i.getCommonName() : i.getName())
                .toList();
    }

    private static List<String> topSodiumContributors(ComputedNutrition n) {
        return n.getItems().stream()
                .filter(i -> i.getNutrients() != null && i.getNutrients().getSodium() > 50)
                .sorted((a, b) -> Double.compare(b.getNutrients().getSodium(), a.getNutrients().getSodium()))
                .limit(3)
                .map(i -> i.getCommonName() != null ? i.getCommonName() : i.getName())
                .toList();
    }

    static String status(double projectedTotal, double goal) {
        if (goal <= 0) return "Under Budget";
        if (projectedTotal > goal) return "Over Budget";
        if (projectedTotal >= goal * NEAR_LIMIT_FRACTION) return "Near Limit (>80%)";
        return "Under Budget";
    }

    static String grade(int score) {
        if (score >= 85) return "A";
        if (score >= 70) return "B";
        if (score >= 55) return "C";
        if (score >= 40) return "D";
        return "F";
    }

    private static int rank(String severity) {
        return switch (severity == null ? "" : severity) {
            case "CRITICAL" -> 4;
            case "HIGH" -> 3;
            case "MODERATE" -> 2;
            case "LOW" -> 1;
            default -> 0;
        };
    }

    private static int clamp(int v) { return Math.max(0, Math.min(100, v)); }
    private static double pct(double part, double whole) { return whole > 0 ? part / whole * 100 : 0; }
    private static double round(double v) { return Math.round(v * 10.0) / 10.0; }
}
