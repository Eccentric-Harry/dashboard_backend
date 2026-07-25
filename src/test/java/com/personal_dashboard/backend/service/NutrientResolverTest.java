package com.personal_dashboard.backend.service;

import com.personal_dashboard.backend.service.FoodReferenceService.FoodMatch;
import com.personal_dashboard.backend.service.NutrientResolver.ExtractedItem;
import com.personal_dashboard.backend.service.NutrientResolver.ResolvedMeal;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the deterministic half of the meal pipeline — the part that replaced
 * model-recalled nutrient values. No API calls, so this runs in the normal suite.
 */
class NutrientResolverTest {

    private static FoodReferenceService reference;
    private static NutrientResolver resolver;

    @BeforeAll
    static void setUp() throws Exception {
        reference = new FoodReferenceService();
        Method load = FoodReferenceService.class.getDeclaredMethod("load");
        load.setAccessible(true);
        load.invoke(reference);
        resolver = new NutrientResolver(reference);
    }

    @Test
    void loadsBothCuratedAndUsdaTables() {
        assertTrue(reference.getFoods().size() > 5000,
                "expected the USDA bundle to be present; run tools/build_food_reference.py");
        assertTrue(reference.getFoods().stream().anyMatch(f -> "CURATED".equals(f.getSource())),
                "curated Indian table missing");
    }

    @Test
    void matchesSouthIndianDishesByAlias() {
        // These are the exact names Stage 1 is instructed to emit.
        for (String query : List.of("idli", "dosa", "sambar", "coconut chutney",
                                    "curd", "chapati", "lemon rice", "medu vada")) {
            FoodMatch match = reference.bestMatch(query);
            assertNotNull(match, "no match for '" + query + "'");
            assertEquals("CURATED", match.food().getSource(),
                    "'" + query + "' should resolve to the curated Indian table, got "
                            + match.food().getName());
        }
    }

    @Test
    void matchesGenericFoodsAgainstUsda() {
        for (String query : List.of("broccoli", "scrambled eggs", "olive oil", "cheddar cheese")) {
            FoodMatch match = reference.bestMatch(query);
            assertNotNull(match, "no match for '" + query + "'");
            assertTrue(match.score() >= 0.45, "weak match for '" + query + "': " + match.score());
        }
    }

    @Test
    void scalesNutrientsLinearlyByGrams() {
        // Curated idli is 105 kcal / 100 g, so three standard 55 g idlis = 165 g.
        ResolvedMeal meal = resolver.resolve(List.of(
                new ExtractedItem("idli", 165, false, 0.9)));

        assertEquals(1, meal.getItems().size());
        assertEquals(105 * 1.65, meal.total("kcal"), 0.5);
        assertEquals(2.7 * 1.65, meal.total("protein"), 0.1);
    }

    @Test
    void totalsAreTheSumOfItems() {
        ResolvedMeal meal = resolver.resolve(List.of(
                new ExtractedItem("idli", 110, false, 0.9),
                new ExtractedItem("sambar", 160, false, 0.8),
                new ExtractedItem("coconut chutney", 36, false, 0.7)));

        assertEquals(3, meal.getItems().size());
        double summed = meal.getItems().stream()
                .mapToDouble(i -> i.getNutrients().get("kcal")).sum();
        assertEquals(summed, meal.total("kcal"), 0.5);
        assertTrue(meal.total("kcal") > 0);
    }

    @Test
    void resolutionIsDeterministic() {
        List<ExtractedItem> items = List.of(
                new ExtractedItem("dosa", 120, false, 0.9),
                new ExtractedItem("sambar", 160, false, 0.8));
        assertEquals(resolver.resolve(items).total("kcal"),
                resolver.resolve(items).total("kcal"),
                "same input must always produce the same macros");
    }

    @Test
    void unmatchedItemsAreReportedNotSilentlyDropped() {
        ResolvedMeal meal = resolver.resolve(List.of(
                new ExtractedItem("idli", 55, false, 0.9),
                new ExtractedItem("zzzz nonexistent foodstuff qqqq", 100, false, 0.3)));

        assertEquals(1, meal.getItems().size());
        assertEquals(1, meal.getUnresolved().size());
        assertEquals(0.5, meal.coverage(), 0.001);
        // The unmatched item must not have quietly contributed calories.
        assertEquals(105 * 0.55, meal.total("kcal"), 0.5);
    }

    @Test
    void atwaterHoldsForCuratedEntries() {
        ResolvedMeal meal = resolver.resolve(List.of(
                new ExtractedItem("idli", 165, false, 0.9),
                new ExtractedItem("sambar", 160, false, 0.9),
                new ExtractedItem("coconut chutney", 36, false, 0.9)));

        assertTrue(meal.isAtwaterConsistent(),
                "energy should track macros; delta was " + meal.getAtwaterDeltaKcal());
    }

    @Test
    void computesGlycaemicLoadFromNetCarbs() {
        // White rice: GI 72, 28.2 g carbs and 0.4 g fibre per 100 g.
        // 200 g -> net carbs (56.4 - 0.8) = 55.6; GL = 72 * 55.6 / 100 = 40.0
        ResolvedMeal meal = resolver.resolve(List.of(
                new ExtractedItem("white rice", 200, false, 0.9)));

        assertEquals(40.0, meal.getGlycemicLoad(), 1.5);
        assertEquals("High (GL>=20)", meal.getGlycemicClassification());
    }

    @Test
    void classifiesLowGlycaemicLoad() {
        ResolvedMeal meal = resolver.resolve(List.of(
                new ExtractedItem("curd", 140, false, 0.9)));
        assertTrue(meal.getGlycemicLoad() < 10, "curd should be low GL");
        assertEquals("Low (GL<10)", meal.getGlycemicClassification());
    }

    @Test
    void saltUsesRealSodiumDensity() {
        // The old prompt had to be told "1 g salt = 400 mg sodium" because the model kept
        // returning 0. The table carries the real figure (~38.8 g Na per 100 g).
        ResolvedMeal meal = resolver.resolve(List.of(
                new ExtractedItem("salt", 2, false, 0.5)));
        assertEquals(775, meal.total("sodium"), 50);
    }

    @Test
    void ignoresZeroAndNegativeWeights() {
        ResolvedMeal meal = resolver.resolve(List.of(
                new ExtractedItem("idli", 0, false, 0.9),
                new ExtractedItem("sambar", -10, false, 0.9)));
        assertTrue(meal.getItems().isEmpty());
        assertEquals(0.0, meal.total("kcal"));
    }

    @Test
    void handlesEmptyAndNullInput() {
        assertEquals(0.0, resolver.resolve(List.of()).total("kcal"));
        assertEquals(0.0, resolver.resolve(null).total("kcal"));
        assertEquals(1.0, resolver.resolve(List.of()).coverage());
    }

    @Test
    void promptBlockCarriesTotalsAndFlagsGaps() {
        ResolvedMeal meal = resolver.resolve(List.of(
                new ExtractedItem("idli", 110, false, 0.9),
                new ExtractedItem("qqqq unknown thing", 50, false, 0.2)));
        String block = meal.toPromptBlock();

        assertTrue(block.contains("TOTALS:"));
        assertTrue(block.contains("GLYCAEMIC LOAD:"));
        assertTrue(block.contains("UNRESOLVED"), "gaps must be visible to Stage 2");
    }

    @Test
    void curatedEntriesAreMarkedVegetarianCorrectly() {
        assertTrue(resolver.resolve(List.of(
                new ExtractedItem("idli", 55, false, 0.9))).isAllVegetarian());
        assertFalse(resolver.resolve(List.of(
                new ExtractedItem("boiled egg", 50, false, 0.9))).isAllVegetarian());
    }
}
