package com.personal_dashboard.backend.service.nutrition;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Fully computed nutrition for a meal — the output of deterministic arithmetic over
 * USDA-resolved ingredients. No value here originates from a language model.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ComputedNutrition {

    @Builder.Default
    private List<Item> items = List.of();

    /** Absolute nutrient totals for the meal. */
    private NutrientProfile totals;

    /** Sum of per-item glycaemic load contributions. */
    private double glycaemicLoad;

    /** {@code Low (GL<10)}, {@code Medium (GL 10-19)} or {@code High (GL>=20)}. */
    private String glClassification;

    /**
     * Energy range implied by the vision stage's portion-confidence brackets.
     * Portion estimation is the dominant error source in image-based dietary assessment,
     * so we carry the uncertainty forward rather than presenting a single false-precision figure.
     */
    private double caloriesLow;
    private double caloriesHigh;

    /** Ingredients for which no authoritative nutrient record could be found. */
    @Builder.Default
    private List<String> unresolvedIngredients = List.of();

    /** Ingredients matched to a non-FDC regional estimate rather than a measured record. */
    @Builder.Default
    private List<String> estimatedIngredients = List.of();

    /** Share of meal energy backed by a measured USDA record, 0.0–1.0. */
    private double dataConfidence;

    /**
     * Share of meal <em>mass</em> that resolved, 0.0–1.0.
     *
     * <p>Reported alongside energy confidence because the two diverge exactly where it hurts
     * most. An unmatched egg white or scoop of whey carries little energy, so energy-weighted
     * confidence stays near 1.0 while the protein total silently loses a third of its value.
     * Mass coverage catches that.</p>
     */
    private double massCoverage;

    /** Total gram weight of ingredients that could not be matched. */
    private double unresolvedGrams;

    /**
     * The vision model's holistic energy estimate for the dish, made independently of its own
     * ingredient list. Null when not supplied.
     */
    private Double dishLevelEstimateKcal;

    /**
     * Ratio of the ingredient sum to the holistic estimate, or null when there is nothing to
     * compare. Well below 1.0 means the decomposition probably missed something — oil absorbed
     * during frying, a second roti, sugar in the chai — which is the failure mode that
     * summing a visible ingredient list is most prone to.
     */
    private Double plausibilityRatio;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Item {
        private int itemId;
        private String name;
        private String commonName;
        private double grams;
        private boolean hidden;

        /** Absolute nutrients for {@link #grams} of this food. */
        private NutrientProfile nutrients;

        private Integer glycaemicIndex;
        private Double glycaemicLoadContribution;

        /** True when backed by a USDA record; false when the lookup failed. */
        private boolean resolved;
        /** {@code EMBEDDED_EXACT}, {@code MONGO_CACHE}, {@code FDC_API}, {@code NO_MATCH}, … */
        private String source;
        /** True when the matched record is a regional estimate rather than a measured FDC entry. */
        private boolean estimatedComposition;
        private double matchConfidence;
        private String matchedDescription;
    }
}
