package com.personal_dashboard.backend.service.nutrition;

import com.personal_dashboard.backend.repository.NutrientCacheRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Verifies that meal totals are exact rather than plausible.
 *
 * <p>These assertions are tight on purpose. The point of moving arithmetic out of the model is
 * that the numbers become reproducible, so a test that only checked "roughly 250 kcal" would
 * defeat the reason the code exists.</p>
 */
class MacroCalculatorTest {

    private MacroCalculator calculator;

    @BeforeEach
    void setUp() {
        NutrientCacheRepository cacheRepository = mock(NutrientCacheRepository.class);
        when(cacheRepository.findByLookupKey(anyString())).thenReturn(Optional.empty());
        when(cacheRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // Keep the test hermetic: embedded table only, no network.
        FdcClient fdcClient = mock(FdcClient.class);
        when(fdcClient.search(anyString())).thenReturn(Optional.empty());

        UsdaNutrientRepository repo = new UsdaNutrientRepository(cacheRepository, fdcClient);
        repo.load();

        calculator = new MacroCalculator(repo);
    }

    private static Stage1Extraction.Item item(int id, String usda, String common, double grams) {
        return Stage1Extraction.Item.builder()
                .itemId(id)
                .usdaFoodDescription(usda)
                .commonName(common)
                .estimatedWeightG(grams)
                .confidenceScore(0.9)
                .build();
    }

    private static Stage1Extraction meal(Stage1Extraction.Item... items) {
        return Stage1Extraction.builder()
                .mealLabel("Test meal")
                .cuisineType("South Indian")
                .ingredients(List.of(items))
                .build();
    }

    @Test
    void scalesUsdaValuesLinearlyByGramWeight() {
        // USDA 169756: cooked white rice is 130 kcal, 2.69 g protein, 28.17 g carbs per 100 g.
        ComputedNutrition n = calculator.compute(meal(
                item(1, "Rice, white, long-grain, regular, enriched, cooked", "White rice", 200)));

        assertEquals(1, n.getItems().size());
        assertTrue(n.getItems().get(0).isResolved(), "cooked white rice should resolve");
        assertEquals(260.0, n.getTotals().getKcal(), 0.01);
        assertEquals(5.38, n.getTotals().getProtein(), 0.01);
        assertEquals(56.34, n.getTotals().getCarbs(), 0.01);
        assertEquals(0.56, n.getTotals().getFat(), 0.01);
    }

    @Test
    void totalsAreTheExactSumOfItems() {
        ComputedNutrition n = calculator.compute(meal(
                item(1, "Rice, white, long-grain, regular, enriched, cooked", "White rice", 150),
                item(2, "Lentils, mature seeds, cooked, boiled, without salt", "Masoor dal", 120),
                item(3, "Oil, vegetable, sunflower", "Cooking oil", 10)));

        double summed = n.getItems().stream().mapToDouble(i -> i.getNutrients().getKcal()).sum();
        assertEquals(summed, n.getTotals().getKcal(), 0.0001,
                "meal total must equal the sum of its items exactly, not approximately");
        assertEquals(3, n.getItems().size());
        assertTrue(n.getItems().stream().allMatch(ComputedNutrition.Item::isResolved));
    }

    @Test
    void saltContributesSodiumFromUsdaCompositionNotAFlatConstant() {
        // USDA 173468: table salt is 38 758 mg sodium per 100 g, i.e. 387.58 mg per gram —
        // the previous prompt instructed the model to use a rounded 400 mg/g.
        ComputedNutrition n = calculator.compute(meal(item(1, "Salt, table", "Salt", 2)));

        assertEquals(775.16, n.getTotals().getSodium(), 0.01);
        assertEquals(0.0, n.getTotals().getKcal(), 0.001, "salt contributes no energy");
    }

    @Test
    void computesGlycaemicLoadFromNetCarbs() {
        // GL = GI x net carbohydrate / 100. White rice GI 73; 200 g gives 56.34 g carbs
        // and 0.8 g fibre, so net carbs are 55.54 g.
        ComputedNutrition n = calculator.compute(meal(
                item(1, "Rice, white, long-grain, regular, enriched, cooked", "White rice", 200)));

        assertEquals(40.5, n.getGlycaemicLoad(), 0.1);
        assertEquals("High (GL>=20)", n.getGlClassification());
    }

    @Test
    void classifiesGlycaemicLoadBands() {
        assertEquals("Low (GL<10)", MacroCalculator.classifyGl(9.9));
        assertEquals("Medium (GL 10-19)", MacroCalculator.classifyGl(10.0));
        assertEquals("Medium (GL 10-19)", MacroCalculator.classifyGl(19.9));
        assertEquals("High (GL>=20)", MacroCalculator.classifyGl(20.0));
    }

    @Test
    void keepsUnmatchedIngredientsVisibleAndExcludesThemFromTotals() {
        ComputedNutrition n = calculator.compute(meal(
                item(1, "Rice, white, long-grain, regular, enriched, cooked", "White rice", 100),
                item(2, "Xyzzy nonexistent foodstuff qqqq", "Mystery item", 50)));

        assertEquals(2, n.getItems().size(), "an unmatched item must still be shown to the user");
        assertEquals(1, n.getUnresolvedIngredients().size());
        assertEquals("Mystery item", n.getUnresolvedIngredients().get(0));
        assertEquals(130.0, n.getTotals().getKcal(), 0.01,
                "unmatched items contribute nothing rather than a guess");
        assertTrue(n.getDataConfidence() > 0.99,
                "confidence is energy-weighted, and the unmatched item carries no energy");
    }

    @Test
    void propagatesPortionUncertaintyIntoAnEnergyBand() {
        Stage1Extraction.Item withRange = item(1,
                "Rice, white, long-grain, regular, enriched, cooked", "White rice", 200);
        withRange.setConfidenceRangeG(Stage1Extraction.Range.builder().low(150).high(260).build());

        ComputedNutrition n = calculator.compute(meal(withRange));

        assertEquals(195.0, n.getCaloriesLow(), 0.5);
        assertEquals(338.0, n.getCaloriesHigh(), 0.5);
        assertTrue(n.getCaloriesLow() < n.getTotals().getKcal());
        assertTrue(n.getCaloriesHigh() > n.getTotals().getKcal());
    }

    @Test
    void appliesADefaultUncertaintyBandWhenTheModelGivesNoRange() {
        ComputedNutrition n = calculator.compute(meal(
                item(1, "Rice, white, long-grain, regular, enriched, cooked", "White rice", 200)));

        // +/- 15% of 260 kcal
        assertEquals(221.0, n.getCaloriesLow(), 0.5);
        assertEquals(299.0, n.getCaloriesHigh(), 0.5);
    }

    @Test
    void handlesAnEmptyExtractionWithoutFailing() {
        ComputedNutrition n = calculator.compute(meal());

        assertEquals(0.0, n.getTotals().getKcal(), 0.001);
        assertTrue(n.getItems().isEmpty());
        assertEquals("Low (GL<10)", n.getGlClassification());
    }
}
