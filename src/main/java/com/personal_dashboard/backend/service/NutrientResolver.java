package com.personal_dashboard.backend.service;

import com.personal_dashboard.backend.service.FoodReferenceService.FoodMatch;
import com.personal_dashboard.backend.service.FoodReferenceService.FoodRef;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns Stage-1 extraction ({@code name} + {@code grams}) into exact nutrition, in Java.
 *
 * <p>This replaces the arithmetic Stage 2 used to be asked to perform from recalled USDA
 * values. Everything here is deterministic: the same extraction always yields the same
 * macros, the Atwater relationship holds by construction, and there is no path by which a
 * language model can invent a number. That removes the pipeline's largest error source and
 * simultaneously lets Stage 2's prompt and output shrink dramatically — the model no
 * longer emits a per-ingredient nutrient breakdown or a chain-of-thought scratchpad, which
 * were the bulk of its output tokens.
 *
 * <p>Unresolved items are surfaced rather than silently dropped: an item the table cannot
 * match contributes nothing to the totals, and pretending otherwise would hide a real gap
 * in coverage. They land in {@link ResolvedMeal#getUnresolved()} for the UI and the logs.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class NutrientResolver {

    private final FoodReferenceService foodReference;

    /** Below this match score we would be inventing a resolution, so we decline to. */
    private static final double MIN_MATCH_SCORE = 0.45;

    /** Atwater factors — used only to cross-check table energy, never to overwrite it. */
    private static final double KCAL_PER_G_PROTEIN = 4.0;
    private static final double KCAL_PER_G_CARB = 4.0;
    private static final double KCAL_PER_G_FAT = 9.0;

    private static final List<String> NUTRIENT_KEYS = List.of(
            "kcal", "protein", "carbs", "fat", "fiber", "sugar",
            "sodium", "satFat", "potassium", "cholesterol");

    /** One item as extracted by the vision stage. */
    public record ExtractedItem(String name, double grams, boolean hidden, Double confidence) {}

    public ResolvedMeal resolve(List<ExtractedItem> items) {
        ResolvedMeal meal = new ResolvedMeal();
        if (items == null || items.isEmpty()) return meal;

        for (ExtractedItem item : items) {
            if (item.grams() <= 0) continue;

            FoodMatch match = foodReference.bestMatch(item.name());
            if (match == null || match.score() < MIN_MATCH_SCORE) {
                meal.unresolved.add(item.name());
                log.debug("[NutrientResolver] unresolved '{}' (best score {})",
                        item.name(), match == null ? "none" : String.format("%.2f", match.score()));
                continue;
            }

            FoodRef food = match.food();
            double scale = item.grams() / 100.0;
            Map<String, Double> nutrients = new LinkedHashMap<>();
            for (String key : NUTRIENT_KEYS) {
                nutrients.put(key, round(food.per100g(key) * scale, 2));
            }

            ResolvedItem resolved = new ResolvedItem();
            resolved.extractedName = item.name();
            resolved.matchedName = food.getName();
            resolved.foodId = food.getId();
            resolved.source = food.getSource();
            resolved.matchScore = round(match.score(), 3);
            resolved.grams = round(item.grams(), 1);
            resolved.hidden = item.hidden();
            resolved.extractionConfidence = item.confidence();
            resolved.vegetarian = food.isVegetarian();
            resolved.nutrients = nutrients;

            // GL uses net (available) carbohydrate — fibre is not glycaemic.
            if (food.getGlycemicIndex() != null) {
                double netCarbs = Math.max(0, nutrients.get("carbs") - nutrients.get("fiber"));
                resolved.glycemicIndex = food.getGlycemicIndex();
                resolved.glycemicLoad = round(food.getGlycemicIndex() * netCarbs / 100.0, 2);
            }

            meal.items.add(resolved);
        }

        computeTotals(meal);
        return meal;
    }

    private void computeTotals(ResolvedMeal meal) {
        Map<String, Double> totals = new LinkedHashMap<>();
        for (String key : NUTRIENT_KEYS) totals.put(key, 0.0);

        double glycemicLoad = 0;
        for (ResolvedItem item : meal.items) {
            for (String key : NUTRIENT_KEYS) {
                totals.merge(key, item.nutrients.getOrDefault(key, 0.0), Double::sum);
            }
            if (item.glycemicLoad != null) glycemicLoad += item.glycemicLoad;
        }
        totals.replaceAll((k, v) -> round(v, 1));

        meal.totals = totals;
        meal.glycemicLoad = round(glycemicLoad, 1);
        meal.glycemicClassification = glycemicLoad < 10 ? "Low (GL<10)"
                : glycemicLoad < 20 ? "Medium (GL 10-19)" : "High (GL>=20)";

        // Table energy is measured, not derived, so it stays authoritative. Atwater is a
        // consistency check: a large gap means a bad row or a bad match, worth surfacing.
        double atwater = totals.get("protein") * KCAL_PER_G_PROTEIN
                + totals.get("carbs") * KCAL_PER_G_CARB
                + totals.get("fat") * KCAL_PER_G_FAT;
        meal.atwaterKcal = round(atwater, 1);
        meal.atwaterDeltaKcal = round(totals.get("kcal") - atwater, 1);
        // Fibre and sugar alcohols legitimately open a gap, so the tolerance is relative
        // and generous; this flags structural problems, not rounding.
        meal.atwaterConsistent = totals.get("kcal") <= 0
                || Math.abs(meal.atwaterDeltaKcal) <= Math.max(25.0, totals.get("kcal") * 0.12);

        meal.allVegetarian = meal.items.stream().allMatch(i -> i.vegetarian);
    }

    private static double round(double v, int places) {
        double f = Math.pow(10, places);
        return Math.round(v * f) / f;
    }

    // ─── Result types ─────────────────────────────────────────────────────────

    @Getter
    public static class ResolvedMeal {
        private final List<ResolvedItem> items = new ArrayList<>();
        /** Names the table could not match — reported, never silently absorbed. */
        private final List<String> unresolved = new ArrayList<>();
        private Map<String, Double> totals = new LinkedHashMap<>();
        private double glycemicLoad;
        private String glycemicClassification = "Low (GL<10)";
        private double atwaterKcal;
        private double atwaterDeltaKcal;
        private boolean atwaterConsistent = true;
        private boolean allVegetarian = true;

        public double total(String nutrient) {
            return totals.getOrDefault(nutrient, 0.0);
        }

        /** Share of extracted items the table could resolve — a coverage health metric. */
        public double coverage() {
            int total = items.size() + unresolved.size();
            return total == 0 ? 1.0 : (double) items.size() / total;
        }

        /** Compact, token-cheap rendering of the resolved facts for the Stage-2 prompt. */
        public String toPromptBlock() {
            StringBuilder sb = new StringBuilder();
            sb.append("ITEMS (name | grams | kcal | protein g | carbs g | fat g | source):\n");
            for (ResolvedItem i : items) {
                sb.append(String.format("  %s | %.0fg | %.0f | %.1f | %.1f | %.1f | %s%s%n",
                        i.matchedName, i.grams, i.nutrients.get("kcal"),
                        i.nutrients.get("protein"), i.nutrients.get("carbs"),
                        i.nutrients.get("fat"), i.source, i.hidden ? " (inferred)" : ""));
            }
            if (!unresolved.isEmpty()) {
                sb.append("UNRESOLVED (no table entry; excluded from totals): ")
                        .append(String.join(", ", unresolved)).append('\n');
            }
            sb.append(String.format(
                    "TOTALS: %.0f kcal | protein %.1f g | carbs %.1f g | fat %.1f g | "
                            + "fibre %.1f g | sugar %.1f g | sodium %.0f mg | sat fat %.1f g%n",
                    total("kcal"), total("protein"), total("carbs"), total("fat"),
                    total("fiber"), total("sugar"), total("sodium"), total("satFat")));
            sb.append(String.format("GLYCAEMIC LOAD: %.1f (%s)%n",
                    glycemicLoad, glycemicClassification));
            return sb.toString();
        }
    }

    @Getter
    public static class ResolvedItem {
        private String extractedName;
        private String matchedName;
        private String foodId;
        private String source;
        private double matchScore;
        private double grams;
        private boolean hidden;
        private Double extractionConfidence;
        private boolean vegetarian;
        private Double glycemicIndex;
        private Double glycemicLoad;
        private Map<String, Double> nutrients = new LinkedHashMap<>();
    }
}
