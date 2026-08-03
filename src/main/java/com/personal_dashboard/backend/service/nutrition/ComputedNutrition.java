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
