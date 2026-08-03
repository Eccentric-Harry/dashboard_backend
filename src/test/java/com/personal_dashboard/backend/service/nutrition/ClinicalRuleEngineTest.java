package com.personal_dashboard.backend.service.nutrition;

import com.personal_dashboard.backend.dto.GeminiAnalysisResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the guideline thresholds now that they are code rather than prompt text.
 *
 * <p>The value of this move is exactly what these tests demonstrate: the same meal and profile
 * always produce the same flags, so a clinical rule can be reviewed and regression-tested
 * instead of being re-derived by a model on every request.</p>
 */
class ClinicalRuleEngineTest {

    private ClinicalRuleEngine engine;

    @BeforeEach
    void setUp() {
        engine = new ClinicalRuleEngine();
    }

    private static NutritionContext.NutritionContextBuilder baseProfile() {
        return NutritionContext.builder()
                .displayName("Test User")
                .age(30).biologicalSex("MALE")
                .heightCm(175).weightKg(70).bmi(22.9)
                .activityLevel("MODERATELY_ACTIVE")
                .tdee(2400).primaryGoal("MAINTAIN")
                .goalCalories(2400).goalProteinG(120).goalCarbsG(280).goalFatG(70)
                .goalSodiumMg(2300).goalFiberG(25).goalAddedSugarMaxG(36)
                .medicalConditions(List.of());
    }

    private static ComputedNutrition mealWith(NutrientProfile totals, double gl) {
        return ComputedNutrition.builder()
                .items(List.of())
                .totals(totals)
                .glycaemicLoad(gl)
                .glClassification(MacroCalculator.classifyGl(gl))
                .dataConfidence(1.0)
                .build();
    }

    private static NutrientProfile totals(double kcal, double protein, double carbs,
                                          double fat, double satFat, double fiber,
                                          double sugar, double sodium, double potassium) {
        return NutrientProfile.builder()
                .kcal(kcal).protein(protein).carbs(carbs).fat(fat).satFat(satFat)
                .fiber(fiber).sugar(sugar).sodium(sodium).potassium(potassium).build();
    }

    private static boolean hasTitle(List<GeminiAnalysisResult.ClinicalFlag> flags, String fragment) {
        return flags.stream().anyMatch(f -> f.getTitle().contains(fragment));
    }

    @Test
    void flagsHighGlycaemicLoadForEveryoneAtOrAboveTwenty() {
        var flags = engine.evaluate(
                mealWith(totals(600, 15, 120, 8, 2, 4, 5, 300, 400), 25.0),
                baseProfile().build());

        assertTrue(hasTitle(flags, "High Glycaemic Load"));
        assertEquals("HIGH", flags.get(0).getSeverity());
        assertEquals("General Population", flags.get(0).getConditionLink());
    }

    @Test
    void escalatesUrgencyAndAttributionWhenDiabetesIsPresent() {
        var flags = engine.evaluate(
                mealWith(totals(600, 15, 120, 8, 2, 4, 5, 300, 400), 25.0),
                baseProfile().medicalConditions(List.of("Type 2 Diabetes")).build());

        var gl = flags.stream().filter(f -> f.getTitle().contains("High Glycaemic Load")).findFirst().orElseThrow();
        assertEquals("Diabetes / Insulin Resistance", gl.getConditionLink());
        assertEquals("Avoid", gl.getUrgency());
        assertTrue(gl.getEvidenceBasis().contains("ADA"));
    }

    @Test
    void doesNotFlagModerateGlycaemicLoadForAnUnaffectedUser() {
        var flags = engine.evaluate(
                mealWith(totals(500, 25, 60, 15, 4, 8, 5, 300, 500), 14.0),
                baseProfile().build());

        assertFalse(hasTitle(flags, "Glycaemic Load"),
                "a medium GL is unremarkable without an insulin-related condition");
    }

    @Test
    void flagsModerateGlycaemicLoadForPcos() {
        var flags = engine.evaluate(
                mealWith(totals(500, 25, 60, 15, 4, 8, 5, 300, 500), 14.0),
                baseProfile().medicalConditions(List.of("PCOS")).build());

        assertTrue(hasTitle(flags, "Moderate Glycaemic Load"));
    }

    @Test
    void appliesTheStricterSodiumCeilingForKidneyDisease() {
        NutrientProfile t = totals(500, 25, 50, 20, 5, 5, 3, 550, 300);

        var healthy = engine.evaluate(mealWith(t, 8.0), baseProfile().build());
        var ckd = engine.evaluate(mealWith(t, 8.0),
                baseProfile().medicalConditions(List.of("CKD stage 3")).goalSodiumMg(1500).build());

        assertFalse(hasTitle(healthy, "Sodium"),
                "550 mg is below the 600 mg general threshold");
        assertTrue(hasTitle(ckd, "High Sodium Load"),
                "550 mg exceeds the 500 mg ceiling applied in kidney disease");
    }

    @Test
    void flagsCarbohydrateAboveAdaSingleMealGuidanceOnNetCarbs() {
        // 75 g total carbohydrate less 10 g fibre is 65 g net, above the 60 g ceiling.
        var flags = engine.evaluate(
                mealWith(totals(600, 30, 75, 15, 4, 10, 5, 300, 500), 18.0),
                baseProfile().medicalConditions(List.of("Type 2 Diabetes")).build());

        assertTrue(hasTitle(flags, "Single-Meal Carbohydrate"));
    }

    @Test
    void computesBudgetAgainstWhatWasActuallyEatenToday() {
        var ctx = baseProfile()
                .consumedCalories(1800).consumedProteinG(90)
                .consumedCarbsG(200).consumedFatG(50).consumedSodiumMg(1500)
                .build();
        var budget = engine.budget(mealWith(totals(700, 30, 80, 25, 6, 6, 8, 900, 500), 15.0), ctx);

        // 2400 goal less 1800 already eaten leaves 600 before the meal.
        assertEquals(600.0, budget.getRemainingBudgetBeforeThisMeal().getCaloriesKcal(), 0.1);
        // A 700 kcal meal takes it 100 kcal negative.
        assertEquals(-100.0, budget.getRemainingBudgetAfterThisMeal().getCaloriesKcal(), 0.1);
        assertEquals("Over Budget", budget.getBudgetStatus().getCalories());
        assertEquals("Over Budget", budget.getBudgetStatus().getSodium());
    }

    @Test
    void reportsNearLimitOnlyInsideTheEightyPercentBand() {
        assertEquals("Under Budget", ClinicalRuleEngine.status(79, 100));
        assertEquals("Near Limit (>80%)", ClinicalRuleEngine.status(80, 100));
        assertEquals("Near Limit (>80%)", ClinicalRuleEngine.status(100, 100));
        assertEquals("Over Budget", ClinicalRuleEngine.status(101, 100));
    }

    @Test
    void scoresAWellBalancedMealAboveAPoorOne() {
        var ctx = baseProfile().build();

        var good = engine.score(
                mealWith(totals(500, 35, 45, 15, 3, 10, 4, 350, 800), 8.0), ctx, List.of());
        var poor = mealWith(totals(800, 10, 130, 28, 12, 2, 40, 1400, 200), 32.0);
        var poorScore = engine.score(poor, ctx, engine.evaluate(poor, ctx));

        assertTrue(good.getOverallScore() > poorScore.getOverallScore(),
                "a balanced low-GL meal must outscore a high-GL, high-sodium one");
        assertTrue(good.getOverallScore() >= 70, "expected at least a B, got " + good.getOverallScore());
        assertTrue(poorScore.getOverallScore() < 55, "expected below C, got " + poorScore.getOverallScore());
    }

    @Test
    void gradingBandsAreStable() {
        assertEquals("A", ClinicalRuleEngine.grade(85));
        assertEquals("B", ClinicalRuleEngine.grade(70));
        assertEquals("C", ClinicalRuleEngine.grade(55));
        assertEquals("D", ClinicalRuleEngine.grade(40));
        assertEquals("F", ClinicalRuleEngine.grade(39));
    }

    @Test
    void isDeterministicAcrossRepeatedEvaluations() {
        var ctx = baseProfile().medicalConditions(List.of("Acne", "Hypertension")).build();
        var meal = mealWith(totals(750, 20, 110, 25, 9, 5, 20, 950, 600), 28.0);

        var first = engine.evaluate(meal, ctx);
        var second = engine.evaluate(meal, ctx);

        assertEquals(first.size(), second.size());
        for (int i = 0; i < first.size(); i++) {
            assertEquals(first.get(i).getTitle(), second.get(i).getTitle());
            assertEquals(first.get(i).getSeverity(), second.get(i).getSeverity());
            assertEquals(first.get(i).getQuantifiedRisk(), second.get(i).getQuantifiedRisk());
        }
        assertEquals(engine.score(meal, ctx, first).getOverallScore(),
                engine.score(meal, ctx, second).getOverallScore());
    }

    @Test
    void surfacesUnmatchedIngredientsAsADataQualityFlag() {
        ComputedNutrition n = ComputedNutrition.builder()
                .items(List.of())
                .totals(totals(300, 15, 40, 8, 2, 5, 3, 200, 300))
                .glycaemicLoad(6.0)
                .glClassification("Low (GL<10)")
                .unresolvedIngredients(List.of("Mystery chutney"))
                .dataConfidence(0.8)
                .build();

        var flags = engine.evaluate(n, baseProfile().build());

        assertTrue(hasTitle(flags, "Not Matched"),
                "the user should be told the totals are incomplete, not silently given a low number");
    }
}
