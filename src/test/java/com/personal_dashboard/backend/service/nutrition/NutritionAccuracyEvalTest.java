package com.personal_dashboard.backend.service.nutrition;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.personal_dashboard.backend.repository.NutrientCacheRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Measures nutrition accuracy against ground truth and prints a MAPE report.
 *
 * <h2>What this measures, and what it does not</h2>
 *
 * <p>Fixtures state ingredients at known weights; the harness runs them through the real
 * resolver and arithmetic and reports MAPE against reference totals.</p>
 *
 * <p><b>Be clear about what this is.</b> The reference totals are the sum of the reference
 * table's own per-100 g composition at the stated weights. That makes this a <em>regression
 * guard</em>, not an independent accuracy measurement: it fires when the resolver starts
 * matching a different record, when the arithmetic drifts, or when an ingredient stops
 * resolving — the three ways this layer could silently start corrupting a health log. It
 * cannot tell you the pipeline is right in absolute terms, because it and the pipeline read
 * the same table. (The table's own values are checked against published USDA figures
 * separately, in {@link MacroCalculatorTest} and {@link UsdaNutrientRepositoryTest}.)</p>
 *
 * <p>It also does <b>not</b> measure portion estimation, which belongs to the vision stage
 * and is the larger error source overall — published evaluations put LLM weight estimation
 * near 36% MAPE. Both gaps close the same way: add cases with real photographs and
 * scale-weighed reference values via the fixture's {@code photoPath} field. Those become
 * genuinely independent ground truth, and the harness reports them in the same table.</p>
 *
 * <p>Results are bucketed by whether a case touches a regional IFCT-derived entry, so the
 * weaker path is visible rather than averaged into the stronger one.</p>
 */
class NutritionAccuracyEvalTest {

    /**
     * Per-nutrient MAPE ceiling. Anything the resolver matches correctly should land within
     * rounding distance, so these are set just above rounding noise rather than at a
     * "good enough for nutrition" tolerance.
     */
    private static final double MAX_MAPE_MEASURED = 0.5;

    /**
     * Cases containing a regional IFCT-derived entry are held to a looser bound. Those
     * entries are flagged {@code estimated:true} in the nutrient table and surfaced to the
     * user as reduced confidence precisely because they are not analytically measured.
     */
    private static final double MAX_MAPE_REGIONAL = 3.0;

    private MacroCalculator calculator;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        NutrientCacheRepository cacheRepository = mock(NutrientCacheRepository.class);
        when(cacheRepository.findByLookupKey(anyString())).thenReturn(Optional.empty());
        when(cacheRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // Embedded table only: an eval that silently depended on a network service
        // would measure the network as much as the code.
        FdcClient fdcClient = mock(FdcClient.class);
        when(fdcClient.search(anyString())).thenReturn(Optional.empty());

        UsdaNutrientRepository repo = new UsdaNutrientRepository(cacheRepository, fdcClient);
        repo.load();
        calculator = new MacroCalculator(repo);
    }

    private record Metric(String name, double mape, double worst, String worstCase) {}

    @Test
    @DisplayName("Nutrition accuracy evaluation — MAPE against ground-truth fixtures")
    void evaluate() throws Exception {
        JsonNode root;
        try (var in = new ClassPathResource("nutrition-eval/meals.json").getInputStream()) {
            root = objectMapper.readTree(in);
        }
        JsonNode cases = root.path("cases");
        assertTrue(cases.size() >= 10, "eval set should be large enough to be meaningful");

        String[] nutrients = {"kcal", "protein", "carbs", "fat", "sodium"};
        double[][] errors = new double[nutrients.length][cases.size()];
        String[][] caseIds = new String[nutrients.length][cases.size()];
        boolean[] regional = new boolean[cases.size()];

        int unresolvedTotal = 0;
        int ingredientTotal = 0;
        List<String> unresolvedNames = new ArrayList<>();

        StringBuilder perCase = new StringBuilder();
        perCase.append(String.format("%n%-28s %10s %10s %8s%n", "CASE", "COMPUTED", "REFERENCE", "ERR%"));
        perCase.append("-".repeat(60)).append("\n");

        for (int c = 0; c < cases.size(); c++) {
            JsonNode testCase = cases.get(c);
            String id = testCase.path("id").asText();
            regional[c] = testCase.path("containsRegionalEstimate").asBoolean(false);

            List<Stage1Extraction.Item> items = new ArrayList<>();
            int itemId = 1;
            for (JsonNode ing : testCase.path("ingredients")) {
                items.add(Stage1Extraction.Item.builder()
                        .itemId(itemId++)
                        .usdaFoodDescription(ing.path("usda").asText())
                        .commonName(ing.path("common").asText())
                        .estimatedWeightG(ing.path("grams").asDouble())
                        .confidenceScore(1.0)
                        .build());
            }

            ComputedNutrition computed = calculator.compute(
                    Stage1Extraction.builder().mealLabel(id).ingredients(items).build());

            ingredientTotal += items.size();
            unresolvedTotal += computed.getUnresolvedIngredients().size();
            unresolvedNames.addAll(computed.getUnresolvedIngredients());

            JsonNode ref = testCase.path("reference");
            NutrientProfile t = computed.getTotals();
            double[] actual = {t.getKcal(), t.getProtein(), t.getCarbs(), t.getFat(), t.getSodium()};

            for (int n = 0; n < nutrients.length; n++) {
                double expected = ref.path(nutrients[n]).asDouble();
                double err = expected == 0 ? 0 : Math.abs(actual[n] - expected) / expected * 100;
                errors[n][c] = err;
                caseIds[n][c] = id;
            }

            perCase.append(String.format("%-28s %10.1f %10.1f %7.2f%%  %s%n",
                    id + " (kcal)", actual[0], ref.path("kcal").asDouble(), errors[0][c],
                    regional[c] ? "regional est." : ""));
        }

        System.out.println(perCase);
        System.out.printf("%n=== NUTRITION ACCURACY EVAL — %d meals, %d ingredients ===%n",
                cases.size(), ingredientTotal);

        List<Metric> measured = bucket(nutrients, errors, caseIds, regional, false);
        List<Metric> regionalMetrics = bucket(nutrients, errors, caseIds, regional, true);

        report("FDC-MEASURED INGREDIENTS ONLY", measured);
        report("CONTAINS REGIONAL (IFCT) ESTIMATE", regionalMetrics);

        System.out.printf("%nIngredient match rate: %d/%d (%.1f%%)%n",
                ingredientTotal - unresolvedTotal, ingredientTotal,
                100.0 * (ingredientTotal - unresolvedTotal) / ingredientTotal);
        if (!unresolvedNames.isEmpty()) {
            System.out.println("Unmatched: " + String.join(", ", unresolvedNames));
        }
        System.out.println("\nScope: portion size is held constant, so this measures resolution and");
        System.out.println("arithmetic — not the vision stage's weight estimation, which dominates");
        System.out.println("end-to-end error. Add photoPath cases with weighed reference values for that.");

        // An unmatched ingredient drops out of the totals, which would flatter the MAPE
        // rather than harm it — so the eval is only meaningful at a full match rate.
        assertEquals(0, unresolvedTotal,
                "unmatched ingredients would be excluded from totals and distort this eval: "
                        + unresolvedNames);

        for (Metric m : measured) {
            assertTrue(m.mape() <= MAX_MAPE_MEASURED, String.format(
                    "measured %s MAPE %.2f%% exceeds %.1f%% (worst: %s at %.2f%%)",
                    m.name(), m.mape(), MAX_MAPE_MEASURED, m.worstCase(), m.worst()));
        }
        for (Metric m : regionalMetrics) {
            assertTrue(m.mape() <= MAX_MAPE_REGIONAL, String.format(
                    "regional %s MAPE %.2f%% exceeds %.1f%% (worst: %s at %.2f%%)",
                    m.name(), m.mape(), MAX_MAPE_REGIONAL, m.worstCase(), m.worst()));
        }
    }

    private static List<Metric> bucket(String[] nutrients, double[][] errors, String[][] caseIds,
                                       boolean[] regional, boolean wantRegional) {
        List<Metric> out = new ArrayList<>();
        for (int n = 0; n < nutrients.length; n++) {
            double sum = 0, worst = 0;
            int count = 0;
            String worstCase = "";
            for (int c = 0; c < regional.length; c++) {
                if (regional[c] != wantRegional) continue;
                count++;
                sum += errors[n][c];
                if (errors[n][c] > worst) {
                    worst = errors[n][c];
                    worstCase = caseIds[n][c];
                }
            }
            out.add(new Metric(nutrients[n], count == 0 ? 0 : sum / count, worst, worstCase));
        }
        return out;
    }

    private static void report(String title, List<Metric> metrics) {
        System.out.printf("%n%s%n", title);
        System.out.printf("%-12s %10s %10s   %s%n", "NUTRIENT", "MAPE", "WORST", "WORST CASE");
        System.out.println("-".repeat(60));
        for (Metric m : metrics) {
            System.out.printf("%-12s %9.2f%% %9.2f%%   %s%n", m.name(), m.mape(), m.worst(), m.worstCase());
        }
    }

    @Test
    @DisplayName("Repeated evaluation is bit-identical — the pipeline cannot drift")
    void isReproducible() throws Exception {
        JsonNode root;
        try (var in = new ClassPathResource("nutrition-eval/meals.json").getInputStream()) {
            root = objectMapper.readTree(in);
        }
        JsonNode first = root.path("cases").get(0);

        List<Stage1Extraction.Item> items = new ArrayList<>();
        int itemId = 1;
        for (JsonNode ing : first.path("ingredients")) {
            items.add(Stage1Extraction.Item.builder()
                    .itemId(itemId++)
                    .usdaFoodDescription(ing.path("usda").asText())
                    .commonName(ing.path("common").asText())
                    .estimatedWeightG(ing.path("grams").asDouble())
                    .build());
        }
        Stage1Extraction meal = Stage1Extraction.builder().mealLabel("x").ingredients(items).build();

        ComputedNutrition a = calculator.compute(meal);
        ComputedNutrition b = calculator.compute(meal);

        // The old pipeline could return different macros for the same plate on
        // consecutive runs. This is the property that replaced that behaviour.
        assertEquals(a.getTotals().getKcal(), b.getTotals().getKcal(), 0.0);
        assertEquals(a.getTotals().getProtein(), b.getTotals().getProtein(), 0.0);
        assertEquals(a.getTotals().getSodium(), b.getTotals().getSodium(), 0.0);
        assertEquals(a.getGlycaemicLoad(), b.getGlycaemicLoad(), 0.0);
    }
}
