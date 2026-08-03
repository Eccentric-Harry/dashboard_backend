package com.personal_dashboard.backend.service.nutrition;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * One entry in the nutrient reference table — either loaded from the embedded
 * {@code nutrition/usda-core.json} resource or materialised from a live
 * FoodData Central API lookup.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class UsdaFood {

    /** FoodData Central identifier, or null for regional entries absent from FDC. */
    private Integer fdcId;

    /** Canonical USDA-style food description. */
    private String description;

    /** Colloquial and regional names that should resolve to this entry. */
    @Builder.Default
    private List<String> aliases = List.of();

    /**
     * Glycaemic index (glucose = 100), or null when not applicable/unknown.
     * Sourced from the International Tables of Glycemic Index and Glycemic Load Values.
     */
    private Integer gi;

    /**
     * True when the composition is not a direct FDC record — e.g. a regional preparation
     * derived from IFCT 2017 or standard recipe composition. Surfaced to the user as
     * reduced confidence rather than hidden.
     */
    @Builder.Default
    private boolean estimated = false;

    /** Composition per 100 g of edible portion. */
    private NutrientProfile per100g;
}
