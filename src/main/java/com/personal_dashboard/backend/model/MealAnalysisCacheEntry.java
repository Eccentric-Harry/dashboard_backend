package com.personal_dashboard.backend.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * Cached output of an expensive pipeline stage, keyed by a content hash of its inputs.
 *
 * <p>A personal food log is highly repetitive — the same breakfast recurs for weeks — so the
 * same photograph and description are analysed over and over. Caching by input hash turns
 * those repeats into zero API calls.</p>
 *
 * <p>Two kinds are stored:</p>
 * <ul>
 *   <li>{@code EXTRACTION} — the vision stage's ingredient list. Independent of the user's
 *       profile, so it is shared across users and hits often.</li>
 *   <li>{@code NARRATIVE} — the clinical prose. Depends on the extraction plus the user's
 *       condition and target signature, but not on the exact remaining budget, which is
 *       recomputed deterministically on every request.</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "meal_analysis_cache")
public class MealAnalysisCacheEntry {

    @Id
    private String id;

    /** {@code EXTRACTION} or {@code NARRATIVE}. */
    private String kind;

    /** SHA-256 over the stage's inputs. */
    @Indexed(unique = true)
    private String cacheKey;

    /** Raw JSON payload of the cached stage output. */
    private String payload;

    private Instant cachedAt;

    /** Number of times this entry has been served, for hit-rate reporting. */
    @Builder.Default
    private long hitCount = 0;
}
