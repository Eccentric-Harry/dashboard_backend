package com.personal_dashboard.backend.service.nutrition;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.personal_dashboard.backend.model.NutrientCacheEntry;
import com.personal_dashboard.backend.repository.NutrientCacheRepository;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves an ingredient description to authoritative per-100 g nutrient composition.
 *
 * <p>This class exists because language models are unreliable at <em>recalling</em> nutrient
 * values. Published evaluation of LLM nutrition estimation attributes roughly 80% of error to
 * incorrect nutrient priors and hallucination, and none to arithmetic. So we let the model do
 * what it is good at — recognising food and estimating portion — and look the numbers up.</p>
 *
 * <p>Resolution order, cheapest first:</p>
 * <ol>
 *   <li>Embedded {@code nutrition/usda-core.json} — in-memory, zero latency, no network.</li>
 *   <li>Mongo {@code nutrient_cache} — previously resolved live lookups, shared across users.</li>
 *   <li>Fuzzy token match against the embedded table.</li>
 *   <li>Live FoodData Central API — only for genuine misses, then cached permanently.</li>
 * </ol>
 *
 * <p>A miss is never fatal: it is negatively cached and reported, and the caller falls back to
 * the model's own estimate with the item flagged as unverified.</p>
 */
@Component
@Slf4j
public class UsdaNutrientRepository {

    /**
     * Preparation-state tokens. A mismatch between query and candidate on these is heavily
     * penalised: cooked white rice is 130 kcal/100 g while raw is 365, so silently matching
     * across states is one of the largest error sources this class is meant to eliminate.
     */
    private static final Set<String> COOK_STATES = Set.of(
            "raw", "cooked", "boiled", "fried", "roasted", "steamed", "grilled", "baked", "dried");

    /** Minimum match score below which we decline to guess and escalate to the API. */
    private static final double FUZZY_ACCEPT_THRESHOLD = 0.55;

    /** Ceiling on how long any single ingredient may hold up the analysis. */
    private static final long LOOKUP_BUDGET_SECONDS = 10;

    /** Small pool: these are IO-bound calls, and the quota guard caps real concurrency anyway. */
    private final java.util.concurrent.ExecutorService lookupExecutor =
            java.util.concurrent.Executors.newFixedThreadPool(4, r -> {
                Thread t = new Thread(r, "usda-lookup");
                t.setDaemon(true);
                return t;
            });

    private final NutrientCacheRepository cacheRepository;
    private final FdcClient fdcClient;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** Exact-key index over the embedded table: normalised description and every alias. */
    private final Map<String, UsdaFood> exactIndex = new ConcurrentHashMap<>();
    /** All embedded foods, for fuzzy scoring. */
    private final List<UsdaFood> embedded = new ArrayList<>();
    /** Pre-tokenised embedded descriptions, index-aligned with {@link #embedded}. */
    private final List<Set<String>> embeddedTokens = new ArrayList<>();

    public UsdaNutrientRepository(NutrientCacheRepository cacheRepository, FdcClient fdcClient) {
        this.cacheRepository = cacheRepository;
        this.fdcClient = fdcClient;
    }

    @PostConstruct
    void load() {
        try (var in = new ClassPathResource("nutrition/usda-core.json").getInputStream()) {
            JsonNode root = objectMapper.readTree(in);
            for (JsonNode node : root.path("foods")) {
                UsdaFood food = objectMapper.treeToValue(node, UsdaFood.class);
                if (food.getPer100g() == null) continue;
                embedded.add(food);
                embeddedTokens.add(tokenize(food.getDescription()));
                exactIndex.putIfAbsent(normalize(food.getDescription()), food);
                for (String alias : food.getAliases()) {
                    exactIndex.putIfAbsent(normalize(alias), food);
                }
            }
            log.info("[UsdaNutrientRepository] Loaded {} embedded foods, {} lookup keys",
                    embedded.size(), exactIndex.size());
        } catch (Exception e) {
            log.error("[UsdaNutrientRepository] Failed to load embedded USDA table — "
                    + "all lookups will fall back to the live API or the model estimate", e);
        }
    }

    /**
     * Resolves an ingredient to a nutrient record.
     *
     * @param description USDA-style description emitted by the vision stage
     * @param commonName  colloquial name, tried as a secondary key
     * @param fdcId       FDC id asserted by the model, trusted only if present in the embedded table
     * @return the resolution outcome; never null, but may be {@link Resolution#unresolved}
     */
    public Resolution resolve(String description, String commonName, Integer fdcId) {
        // 1. Model-asserted FDC id, but only when we can corroborate it locally.
        if (fdcId != null) {
            for (UsdaFood f : embedded) {
                if (fdcId.equals(f.getFdcId())) {
                    return Resolution.hit(f, "EMBEDDED_FDC_ID", 1.0);
                }
            }
        }

        // 2. Exact key on either name.
        for (String candidate : new String[]{description, commonName}) {
            if (candidate == null || candidate.isBlank()) continue;
            UsdaFood f = exactIndex.get(normalize(candidate));
            if (f != null) return Resolution.hit(f, "EMBEDDED_EXACT", 1.0);
        }

        // 3. Shared Mongo cache of previous live lookups.
        String cacheKey = normalize(description != null && !description.isBlank() ? description : commonName);
        if (cacheKey != null && !cacheKey.isBlank()) {
            Optional<NutrientCacheEntry> cached = safeFindCached(cacheKey);
            if (cached.isPresent()) {
                NutrientCacheEntry e = cached.get();
                if (e.isUnresolved()) return Resolution.miss("NEGATIVE_CACHE");
                return Resolution.hit(e.getFood(), "MONGO_CACHE", 0.95);
            }
        }

        // 4. Fuzzy match within the embedded table.
        Scored best = bestFuzzyMatch(description, commonName);
        if (best != null && best.score >= FUZZY_ACCEPT_THRESHOLD) {
            return Resolution.hit(best.food, "EMBEDDED_FUZZY", best.score);
        }

        // 5. Live FoodData Central.
        UsdaFood live = liveLookup(description, commonName);
        if (live != null) {
            persist(cacheKey, live, "FDC_API", false);
            return Resolution.hit(live, "FDC_API", 0.9);
        }

        // 6. Give up, but remember that we did so.
        persist(cacheKey, null, "UNRESOLVED", true);
        return Resolution.miss("NO_MATCH");
    }

    /**
     * Resolves a whole ingredient list, overlapping any network lookups.
     *
     * <p>Most items are answered from memory instantly. The few that reach FoodData Central
     * each cost up to the client's read timeout, so resolving a ten-ingredient meal serially
     * could add double-digit seconds to a request the user is watching. Running them
     * concurrently makes the worst case roughly one lookup deep instead of ten.</p>
     *
     * @return resolutions index-aligned with {@code items}
     */
    public List<Resolution> resolveAll(List<Stage1Extraction.Item> items) {
        if (items == null || items.isEmpty()) return List.of();
        if (items.size() == 1) {
            Stage1Extraction.Item only = items.get(0);
            return List.of(resolve(only.getUsdaFoodDescription(), only.getCommonName(), only.getFdcId()));
        }

        List<java.util.concurrent.CompletableFuture<Resolution>> futures = items.stream()
                .map(i -> java.util.concurrent.CompletableFuture.supplyAsync(
                        () -> resolve(i.getUsdaFoodDescription(), i.getCommonName(), i.getFdcId()),
                        lookupExecutor))
                .toList();

        List<Resolution> out = new ArrayList<>(items.size());
        for (int i = 0; i < futures.size(); i++) {
            try {
                out.add(futures.get(i).get(LOOKUP_BUDGET_SECONDS, java.util.concurrent.TimeUnit.SECONDS));
            } catch (Exception e) {
                // One slow or failed lookup must not sink the whole meal.
                log.warn("[UsdaNutrientRepository] Lookup timed out or failed for '{}': {}",
                        items.get(i).getCommonName(), e.getMessage());
                out.add(Resolution.miss("LOOKUP_TIMEOUT"));
            }
        }
        return out;
    }

    /** Number of foods loaded from the embedded table. */
    public int getEmbeddedFoodCount() { return embedded.size(); }

    /** Number of exact lookup keys, counting every alias. */
    public int getLookupKeyCount() { return exactIndex.size(); }

    // ─── Fuzzy matching ────────────────────────────────────────────────────

    private record Scored(UsdaFood food, double score) {}

    private Scored bestFuzzyMatch(String description, String commonName) {
        Set<String> query = new LinkedHashSet<>();
        query.addAll(tokenize(description));
        query.addAll(tokenize(commonName));
        if (query.isEmpty()) return null;

        Scored best = null;
        for (int i = 0; i < embedded.size(); i++) {
            double score = score(query, embeddedTokens.get(i), embedded.get(i));
            if (best == null || score > best.score) {
                best = new Scored(embedded.get(i), score);
            }
        }
        return best;
    }

    /**
     * Token-containment score with a preparation-state guard.
     *
     * <p>Base score is the fraction of query tokens present in the candidate, which handles
     * USDA's comma-qualified descriptions well ("Rice, white, long-grain, cooked" against
     * "white rice cooked"). Alias tokens are folded in so colloquial names score too.</p>
     */
    private double score(Set<String> query, Set<String> candidateTokens, UsdaFood food) {
        Set<String> candidate = new LinkedHashSet<>(candidateTokens);
        for (String alias : food.getAliases()) {
            candidate.addAll(tokenize(alias));
        }

        long shared = query.stream().filter(candidate::contains).count();
        if (shared == 0) return 0.0;
        double base = (double) shared / query.size();

        // Penalise cooking-state disagreement — a raw/cooked mix-up is a large caloric error.
        String queryState = firstState(query);
        String candidateState = firstState(candidate);
        if (queryState != null && candidateState != null && !queryState.equals(candidateState)) {
            base *= 0.4;
        }

        // Slightly favour tighter candidates so "Salt, table" beats a long description
        // that merely happens to contain the word "salt".
        double specificity = 1.0 - Math.min(candidate.size(), 40) / 120.0;
        return base * (0.75 + 0.25 * specificity);
    }

    private String firstState(Set<String> tokens) {
        for (String t : tokens) {
            if (COOK_STATES.contains(t)) return t;
        }
        return null;
    }

    // ─── Live FoodData Central ─────────────────────────────────────────────

    /**
     * Delegates to {@link FdcClient}, which owns timeouts, quota guarding, and match
     * validation. Prefers the USDA-style description over the colloquial name because
     * FDC indexes its own nomenclature.
     */
    private UsdaFood liveLookup(String description, String commonName) {
        String query = (description != null && !description.isBlank()) ? description : commonName;
        Optional<UsdaFood> found = fdcClient.search(query);
        if (found.isEmpty() && commonName != null && !commonName.isBlank()
                && !commonName.equalsIgnoreCase(query)) {
            // A colloquial second attempt is worth one extra call: the vision model's USDA
            // phrasing is sometimes over-specific and matches nothing.
            found = fdcClient.search(commonName);
        }
        return found.orElse(null);
    }

    // ─── Cache plumbing ────────────────────────────────────────────────────

    private Optional<NutrientCacheEntry> safeFindCached(String key) {
        try {
            return cacheRepository.findByLookupKey(key);
        } catch (Exception e) {
            log.warn("[UsdaNutrientRepository] Nutrient cache read failed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private void persist(String key, UsdaFood food, String source, boolean unresolved) {
        if (key == null || key.isBlank()) return;
        try {
            cacheRepository.save(NutrientCacheEntry.builder()
                    .lookupKey(key)
                    .food(food)
                    .source(source)
                    .unresolved(unresolved)
                    .cachedAt(Instant.now())
                    .build());
        } catch (Exception e) {
            log.warn("[UsdaNutrientRepository] Nutrient cache write failed for '{}': {}", key, e.getMessage());
        }
    }

    // ─── Text helpers ──────────────────────────────────────────────────────

    static String normalize(String s) {
        if (s == null) return "";
        return s.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9 ]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    static Set<String> tokenize(String s) {
        String n = normalize(s);
        if (n.isEmpty()) return Set.of();
        Set<String> out = new LinkedHashSet<>();
        for (String t : n.split(" ")) {
            if (t.length() > 1) out.add(t);
        }
        return out;
    }

    /** Outcome of a nutrient lookup. */
    public record Resolution(UsdaFood food, boolean resolved, String source, double confidence) {
        static Resolution hit(UsdaFood f, String source, double confidence) {
            return new Resolution(f, true, source, confidence);
        }
        static Resolution miss(String source) {
            return new Resolution(null, false, source, 0.0);
        }
    }
}
