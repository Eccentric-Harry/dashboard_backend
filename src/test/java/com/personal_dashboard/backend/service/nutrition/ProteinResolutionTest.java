package com.personal_dashboard.backend.service.nutrition;

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
 * Guards the protein path specifically.
 *
 * <p>Under-reported protein was the symptom that surfaced these bugs, and the mechanism is
 * worth stating: an ingredient that fails to resolve contributes <em>zero</em> to the totals.
 * Protein-dense foods — whey, egg white, soya chunks, paneer — were exactly the ones the
 * table did not carry, so every one of them silently deleted its protein from the meal while
 * energy-weighted confidence stayed near 1.0 because those foods carry little energy.</p>
 *
 * <p>These tests pin both halves: the foods now resolve, and when something still does not,
 * the shortfall is visible.</p>
 */
class ProteinResolutionTest {

    private UsdaNutrientRepository repo;
    private MacroCalculator calculator;

    @BeforeEach
    void setUp() {
        NutrientCacheRepository cacheRepository = mock(NutrientCacheRepository.class);
        when(cacheRepository.findByLookupKey(anyString())).thenReturn(Optional.empty());
        when(cacheRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        FdcClient fdcClient = mock(FdcClient.class);
        when(fdcClient.search(anyString())).thenReturn(Optional.empty());

        repo = new UsdaNutrientRepository(cacheRepository, fdcClient);
        repo.load();
        calculator = new MacroCalculator(repo);
    }

    private void assertResolvesWithProtein(String description, String common, double minProtein) {
        var r = repo.resolve(description, common, null);
        assertTrue(r.resolved(), common + " must resolve — an unmatched item contributes zero protein");
        assertTrue(r.food().getPer100g().getProtein() >= minProtein,
                String.format("%s resolved to '%s' with only %.1f g protein/100 g, expected >= %.1f",
                        common, r.food().getDescription(), r.food().getPer100g().getProtein(), minProtein));
    }

    @Test
    @DisplayName("High-protein staples all resolve instead of silently contributing zero")
    void highProteinFoodsResolve() {
        assertResolvesWithProtein("Whey protein powder", "Whey protein", 60);
        assertResolvesWithProtein("Soy protein concentrate", "Soya chunks", 40);
        assertResolvesWithProtein("Egg, white, raw", "Egg whites", 9);
        assertResolvesWithProtein("Yogurt, Greek, plain, nonfat", "Greek yogurt", 9);
        assertResolvesWithProtein("Chickpea flour (besan)", "Besan", 20);
        assertResolvesWithProtein("Peanut butter, smooth", "Peanut butter", 20);
        assertResolvesWithProtein("Tuna, canned in water", "Canned tuna", 20);
        assertResolvesWithProtein("Cheese, cottage, lowfat", "Cottage cheese", 10);
        assertResolvesWithProtein("Fish, salmon, cooked", "Salmon", 20);
    }

    @Test
    @DisplayName("A cooking method in the query does not turn a valid match into a miss")
    void cookingMethodDoesNotBlockAMatch() {
        // The paneer record states no preparation, so "grilled" is extra information rather
        // than a contradiction. Penalising it made this an outright miss.
        assertResolvesWithProtein("Paneer, grilled", "Grilled paneer", 15);
        assertResolvesWithProtein("Chicken, tandoori, roasted", "Tandoori chicken", 25);
    }

    @Test
    @DisplayName("Raw and cooked forms stay distinct where composition differs sharply")
    void rawAndCookedDoNotCollapse() {
        double rawChicken = repo.resolve("Chicken, broilers or fryers, breast, meat only, raw",
                "Raw chicken breast", null).food().getPer100g().getProtein();
        double cookedChicken = repo.resolve("Chicken, broilers or fryers, breast, meat only, cooked, roasted",
                "Chicken breast", null).food().getPer100g().getProtein();

        // Cooking drives off water and concentrates protein — 22.5 g/100 g raw against
        // 31.0 g/100 g cooked. Collapsing the two is a ~38% protein error.
        assertEquals(22.5, rawChicken, 0.01);
        assertEquals(31.02, cookedChicken, 0.01);

        double rawDal = repo.resolve("Lentils, mature seeds, raw", "Raw masoor dal", null)
                .food().getPer100g().getProtein();
        double cookedDal = repo.resolve("Lentils, mature seeds, cooked, boiled, without salt", "Dal", null)
                .food().getPer100g().getProtein();
        assertEquals(24.63, rawDal, 0.01);
        assertEquals(9.02, cookedDal, 0.01);
    }

    @Test
    @DisplayName("Generic modifiers alone cannot carry a match to the wrong food")
    void genericTokensDoNotProduceWrongFoodMatches() {
        // "Milk, buffalo, fluid" once matched "Yogurt, plain, whole milk" on {milk, whole} —
        // two of the commonest tokens in the table — and buffalo milk was priced as yogurt.
        var r = repo.resolve("Milk, buffalo, whole", "Buffalo milk", null);

        assertTrue(r.resolved());
        assertTrue(r.food().getDescription().toLowerCase().contains("buffalo"),
                "expected the buffalo milk record, got: " + r.food().getDescription());
    }

    @Test
    @DisplayName("Unmatched mass is reported even when energy confidence stays high")
    void unmatchedProteinSourceIsVisible() {
        // A scoop of whey is ~30 g and ~110 kcal against a 600 kcal meal, so energy-weighted
        // confidence barely moves — but 24 g of protein would be missing.
        ComputedNutrition n = calculator.compute(Stage1Extraction.builder()
                .mealLabel("Rice with an unknown supplement")
                .ingredients(List.of(
                        Stage1Extraction.Item.builder().itemId(1)
                                .usdaFoodDescription("Rice, white, long-grain, regular, enriched, cooked")
                                .commonName("White rice").estimatedWeightG(300).build(),
                        Stage1Extraction.Item.builder().itemId(2)
                                .usdaFoodDescription("Qqzz unknown supplement blend")
                                .commonName("Mystery supplement").estimatedWeightG(30).build()))
                .build());

        assertEquals(1, n.getUnresolvedIngredients().size());
        assertEquals(30.0, n.getUnresolvedGrams(), 0.01);
        assertEquals(0.91, n.getMassCoverage(), 0.01);
        assertTrue(n.getDataConfidence() > 0.99,
                "energy-weighted confidence stays high, which is exactly why mass coverage exists");
    }

    @Test
    @DisplayName("A resolved protein source contributes its full protein to the total")
    void proteinFlowsIntoTheTotal() {
        ComputedNutrition n = calculator.compute(Stage1Extraction.builder()
                .mealLabel("Protein shake with milk")
                .ingredients(List.of(
                        Stage1Extraction.Item.builder().itemId(1)
                                .usdaFoodDescription("Whey protein powder").commonName("Whey protein")
                                .estimatedWeightG(30).build(),
                        Stage1Extraction.Item.builder().itemId(2)
                                .usdaFoodDescription("Milk, reduced fat, fluid, 2% milkfat")
                                .commonName("Toned milk").estimatedWeightG(250).build()))
                .build());

        // 30 g whey at 80 g/100 g = 24 g, plus 250 g toned milk at 3.3 g/100 g = 8.25 g.
        assertEquals(32.25, n.getTotals().getProtein(), 0.01);
        assertEquals(1.0, n.getMassCoverage(), 0.001);
        assertTrue(n.getUnresolvedIngredients().isEmpty());
    }
}
