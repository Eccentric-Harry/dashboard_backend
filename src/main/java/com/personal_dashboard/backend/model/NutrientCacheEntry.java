package com.personal_dashboard.backend.model;

import com.personal_dashboard.backend.service.nutrition.UsdaFood;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * Persistent cache of resolved USDA FoodData Central lookups.
 *
 * <p>Deliberately <em>not</em> user-scoped: nutrient composition is global reference data,
 * so a lookup performed for one user benefits every user. This is what lets the live FDC
 * API stay off the hot path — the long tail of ingredients fills in once and is then served
 * from Mongo forever.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "nutrient_cache")
public class NutrientCacheEntry {

    @Id
    private String id;

    /** Normalised lookup key (lower-cased, punctuation-stripped ingredient description). */
    @Indexed(unique = true)
    private String lookupKey;

    /** The resolved food record, including per-100 g composition. */
    private UsdaFood food;

    /** Where the record came from: {@code EMBEDDED}, {@code FDC_API}, or {@code UNRESOLVED}. */
    private String source;

    /** Set for negative caching so a repeatedly-missing term is not re-queried every meal. */
    @Builder.Default
    private boolean unresolved = false;

    private Instant cachedAt;
}
