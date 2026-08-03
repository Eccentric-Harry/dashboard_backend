package com.personal_dashboard.backend.service.nutrition;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns vision-stage ingredients into exact nutrient totals.
 *
 * <p>This replaces the arithmetic the language model used to perform in a chain-of-thought
 * scratchpad. That work was both the single largest output-token cost in the pipeline and
 * unnecessary — evaluations of LLM nutrition estimation find no errors originating from
 * arithmetic. The errors come from the numbers going <em>into</em> the arithmetic, which is
 * exactly what {@link UsdaNutrientRepository} fixes.</p>
 *
 * <p>Every figure produced here is reproducible: the same ingredients and weights always yield
 * the same totals, so a re-analysis can never silently disagree with a stored one.</p>
 */
@Component
@Slf4j
public class MacroCalculator {

    /** Applied when the vision stage gives no explicit confidence bracket for a portion. */
    private static final double DEFAULT_PORTION_UNCERTAINTY = 0.15;

    private final UsdaNutrientRepository nutrients;

    public MacroCalculator(UsdaNutrientRepository nutrients) {
        this.nutrients = nutrients;
    }

    /**
     * Computes exact totals for an extraction.
     *
     * @param extraction the vision stage's ingredient list with gram weights
     * @return computed nutrition, including a portion-uncertainty energy band
     */
    public ComputedNutrition compute(Stage1Extraction extraction) {
        List<ComputedNutrition.Item> items = new ArrayList<>();
        List<String> unresolved = new ArrayList<>();
        List<String> estimated = new ArrayList<>();

        NutrientProfile totals = NutrientProfile.builder().build();
        double glTotal = 0.0;
        double kcalLow = 0.0;
        double kcalHigh = 0.0;
        double kcalResolved = 0.0;

        List<Stage1Extraction.Item> rawItems = safe(extraction);
        // Resolved as a batch so any network lookups overlap rather than queueing.
        List<UsdaNutrientRepository.Resolution> resolutions = nutrients.resolveAll(rawItems);

        for (int idx = 0; idx < rawItems.size(); idx++) {
            Stage1Extraction.Item raw = rawItems.get(idx);
            double grams = Math.max(raw.getEstimatedWeightG(), 0.0);
            String label = raw.getCommonName() != null ? raw.getCommonName() : raw.getUsdaFoodDescription();

            UsdaNutrientRepository.Resolution res = resolutions.get(idx);

            if (!res.resolved()) {
                // Keep the item visible rather than dropping it — an ingredient we cannot price
                // is still an ingredient the user ate, and hiding it would understate the meal.
                unresolved.add(label);
                items.add(ComputedNutrition.Item.builder()
                        .itemId(raw.getItemId())
                        .name(raw.getUsdaFoodDescription())
                        .commonName(raw.getCommonName())
                        .grams(grams)
                        .hidden(raw.isHidden())
                        .nutrients(NutrientProfile.builder().build())
                        .resolved(false)
                        .source(res.source())
                        .matchConfidence(0.0)
                        .build());
                continue;
            }

            UsdaFood food = res.food();
            NutrientProfile scaled = food.getPer100g().scaleTo(grams);

            Integer gi = food.getGi();
            Double glContribution = null;
            if (gi != null && gi > 0) {
                glContribution = gi * scaled.netCarbs() / 100.0;
                glTotal += glContribution;
            }

            totals = totals.plus(scaled);
            kcalResolved += scaled.getKcal();

            double low = raw.getConfidenceRangeG() != null && raw.getConfidenceRangeG().getLow() > 0
                    ? raw.getConfidenceRangeG().getLow()
                    : grams * (1 - DEFAULT_PORTION_UNCERTAINTY);
            double high = raw.getConfidenceRangeG() != null && raw.getConfidenceRangeG().getHigh() > 0
                    ? raw.getConfidenceRangeG().getHigh()
                    : grams * (1 + DEFAULT_PORTION_UNCERTAINTY);
            kcalLow += food.getPer100g().scaleTo(low).getKcal();
            kcalHigh += food.getPer100g().scaleTo(high).getKcal();

            if (food.isEstimated()) {
                estimated.add(label);
            }

            items.add(ComputedNutrition.Item.builder()
                    .itemId(raw.getItemId())
                    .name(food.getDescription())
                    .commonName(raw.getCommonName())
                    .grams(grams)
                    .hidden(raw.isHidden())
                    .nutrients(scaled)
                    .glycaemicIndex(gi)
                    .glycaemicLoadContribution(glContribution)
                    .resolved(true)
                    .source(res.source())
                    .estimatedComposition(food.isEstimated())
                    .matchConfidence(res.confidence())
                    .matchedDescription(food.getDescription())
                    .build());
        }

        double totalKcal = totals.getKcal();
        double confidence = totalKcal > 0 ? Math.min(kcalResolved / totalKcal, 1.0) : (items.isEmpty() ? 0.0 : 1.0);

        return ComputedNutrition.builder()
                .items(items)
                .totals(totals)
                .glycaemicLoad(round(glTotal, 1))
                .glClassification(classifyGl(glTotal))
                .caloriesLow(round(kcalLow, 0))
                .caloriesHigh(round(kcalHigh, 0))
                .unresolvedIngredients(unresolved)
                .estimatedIngredients(estimated)
                .dataConfidence(round(confidence, 2))
                .build();
    }

    /** Standard glycaemic-load bands (Foster-Powell / Brand-Miller). */
    static String classifyGl(double gl) {
        if (gl < 10) return "Low (GL<10)";
        if (gl < 20) return "Medium (GL 10-19)";
        return "High (GL>=20)";
    }

    private static List<Stage1Extraction.Item> safe(Stage1Extraction e) {
        return (e == null || e.getIngredients() == null) ? List.of() : e.getIngredients();
    }

    static double round(double v, int dp) {
        double f = Math.pow(10, dp);
        return Math.round(v * f) / f;
    }
}
