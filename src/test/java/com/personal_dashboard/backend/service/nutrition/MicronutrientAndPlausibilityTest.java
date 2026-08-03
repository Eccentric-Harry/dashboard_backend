package com.personal_dashboard.backend.service.nutrition;

import com.personal_dashboard.backend.dto.GeminiAnalysisResult;
import com.personal_dashboard.backend.repository.NutrientCacheRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Covers micronutrient handling and the whole-dish plausibility cross-check.
 */
class MicronutrientAndPlausibilityTest {

    private MacroCalculator calculator;
    private ClinicalRuleEngine engine;

    @BeforeEach
    void setUp() {
        NutrientCacheRepository cacheRepository = mock(NutrientCacheRepository.class);
        when(cacheRepository.findByLookupKey(anyString())).thenReturn(Optional.empty());
        when(cacheRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        FdcClient fdcClient = mock(FdcClient.class);
        when(fdcClient.search(anyString())).thenReturn(Optional.empty());

        UsdaNutrientRepository repo = new UsdaNutrientRepository(cacheRepository, fdcClient);
        repo.load();
        calculator = new MacroCalculator(repo);
        engine = new ClinicalRuleEngine();
    }

    private static Stage1Extraction.Item item(int id, String usda, String common, double g) {
        return Stage1Extraction.Item.builder().itemId(id)
                .usdaFoodDescription(usda).commonName(common).estimatedWeightG(g).build();
    }

    @Test
    @DisplayName("Micronutrients scale with portion like every other nutrient")
    void microsScaleWithWeight() {
        // Spinach is 2.71 mg iron per 100 g, so 200 g is 5.42 mg.
        ComputedNutrition n = calculator.compute(Stage1Extraction.builder()
                .mealLabel("Spinach").ingredients(List.of(
                        item(1, "Spinach, raw", "Palak", 200))).build());

        assertEquals(5.42, n.getTotals().getMicros().get("iron_mg"), 0.01);
        assertEquals(198.0, n.getTotals().getMicros().get("calcium_mg"), 0.01);
        assertEquals(56.2, n.getTotals().getMicros().get("vitamin_c_mg"), 0.01);
    }

    @Test
    @DisplayName("Micronutrients sum across ingredients")
    void microsSumAcrossIngredients() {
        ComputedNutrition n = calculator.compute(Stage1Extraction.builder()
                .mealLabel("Dal with spinach").ingredients(List.of(
                        item(1, "Spinach, raw", "Palak", 100),
                        item(2, "Lentils, mature seeds, cooked, boiled, without salt", "Dal", 100)))
                .build());

        // 2.71 mg from spinach plus 3.33 mg from lentils.
        assertEquals(6.04, n.getTotals().getMicros().get("iron_mg"), 0.01);
    }

    @Test
    @DisplayName("An unmeasured nutrient is absent, never reported as zero")
    void unmeasuredNutrientsAreAbsentNotZero() {
        // Sunflower oil carries no micronutrient measurements in the table.
        ComputedNutrition n = calculator.compute(Stage1Extraction.builder()
                .mealLabel("Oil only").ingredients(List.of(
                        item(1, "Oil, vegetable, sunflower", "Cooking oil", 10))).build());

        assertFalse(n.getTotals().getMicros().containsKey("iron_mg"),
                "absent must mean unmeasured — reporting 0 mg would assert a fact nobody checked");
        assertTrue(n.getTotals().getMicros().isEmpty());
    }

    @Test
    @DisplayName("An ingredient total far below the whole-dish estimate is flagged")
    void lowPlausibilityRatioIsFlagged() {
        // The model reads the dish as ~800 kcal but the itemised list only reaches ~260 —
        // the signature of missed cooking oil or an uncounted portion.
        ComputedNutrition n = calculator.compute(Stage1Extraction.builder()
                .mealLabel("Fried rice")
                .dishLevelEnergyEstimateKcal(800.0)
                .ingredients(List.of(
                        item(1, "Rice, white, long-grain, regular, enriched, cooked", "Rice", 200)))
                .build());

        assertNotNull(n.getPlausibilityRatio());
        assertEquals(0.33, n.getPlausibilityRatio(), 0.01);

        var flags = engine.evaluate(n, baseContext());
        assertTrue(flags.stream().anyMatch(f -> f.getTitle().contains("Well Below")),
                "a large shortfall against the dish-level read should be surfaced");
    }

    @Test
    @DisplayName("A total in line with the whole-dish estimate raises nothing")
    void agreeingEstimatesRaiseNoFlag() {
        ComputedNutrition n = calculator.compute(Stage1Extraction.builder()
                .mealLabel("Rice")
                .dishLevelEnergyEstimateKcal(270.0)
                .ingredients(List.of(
                        item(1, "Rice, white, long-grain, regular, enriched, cooked", "Rice", 200)))
                .build());

        assertEquals(0.96, n.getPlausibilityRatio(), 0.02);
        assertFalse(engine.evaluate(n, baseContext()).stream()
                        .anyMatch(f -> f.getTitle().contains("Expected Range")),
                "agreement between the two reads is not worth a flag");
    }

    @Test
    @DisplayName("No cross-check is attempted when the model gives no dish-level estimate")
    void absentEstimateIsNotTreatedAsZero() {
        ComputedNutrition n = calculator.compute(Stage1Extraction.builder()
                .mealLabel("Rice").ingredients(List.of(
                        item(1, "Rice, white, long-grain, regular, enriched, cooked", "Rice", 200)))
                .build());

        assertNull(n.getPlausibilityRatio());
        assertFalse(engine.evaluate(n, baseContext()).stream()
                .anyMatch(f -> f.getTitle().contains("Expected Range")));
    }

    private static NutritionContext baseContext() {
        return NutritionContext.builder()
                .displayName("Test").age(30).biologicalSex("MALE")
                .heightCm(175).weightKg(70).bmi(22.9).activityLevel("MODERATELY_ACTIVE")
                .tdee(2400).primaryGoal("MAINTAIN")
                .goalCalories(2400).goalProteinG(120).goalCarbsG(280).goalFatG(70)
                .goalSodiumMg(2300).goalFiberG(25).goalAddedSugarMaxG(36)
                .medicalConditions(List.of()).build();
    }

    @SuppressWarnings("unused")
    private static void unusedTypeAnchor(GeminiAnalysisResult r) { }
}
