package com.personal_dashboard.backend.service.nutrition;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.*;

/**
 * Client for the USDA FoodData Central search API.
 *
 * <p>This sits on a user-facing request path, so it is built to fail fast and fail safe.
 * Every failure mode — timeout, 429, malformed record, poor match — returns empty rather
 * than throwing, and the caller reports the ingredient as unmatched. An unmatched ingredient
 * is visible and honest; a wrong nutrient record silently corrupts a health log.</p>
 */
@Component
@Slf4j
public class FdcClient {

    /** USDA nutrient numbers, stable across dataset versions. */
    private static final String N_ENERGY = "208";
    private static final String N_PROTEIN = "203";
    private static final String N_CARBS = "205";
    private static final String N_FAT = "204";
    private static final String N_SATFAT = "606";
    private static final String N_FIBER = "291";
    private static final String N_SUGAR = "269";
    private static final String N_SODIUM = "307";
    private static final String N_POTASSIUM = "306";
    private static final String N_CHOLESTEROL = "601";

    /**
     * Dataset preference. Foundation and SR Legacy are analytically measured single foods;
     * Survey (FNDDS) covers prepared and mixed dishes, which matters for composite meals.
     * Lower index wins ties on relevance.
     */
    private static final List<String> DATA_TYPE_PREFERENCE =
            List.of("Foundation", "SR Legacy", "Survey (FNDDS)", "Branded");

    /** Physiological ceiling: pure fat is ~900 kcal/100 g, so anything above is a bad record. */
    private static final double MAX_PLAUSIBLE_KCAL_PER_100G = 902.0;

    /** Minimum share of query tokens that must appear in the returned description. */
    private static final double MIN_TOKEN_OVERLAP = 0.34;

    @Value("${nutrition.usda.api-key:}")
    private String apiKey;

    @Value("${nutrition.usda.search-endpoint:https://api.nal.usda.gov/fdc/v1/foods/search}")
    private String searchEndpoint;

    @Value("${nutrition.usda.live-lookup-enabled:true}")
    private boolean enabled;

    @Value("${nutrition.usda.connect-timeout-ms:2000}")
    private int connectTimeoutMs;

    @Value("${nutrition.usda.read-timeout-ms:4000}")
    private int readTimeoutMs;

    /**
     * Branded records are label-derived and far noisier than the analytical datasets.
     * Off by default: a wrong branded match is worse than no match at all.
     */
    @Value("${nutrition.usda.include-branded:false}")
    private boolean includeBranded;

    private final FdcRateLimiter rateLimiter;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private RestTemplate restTemplate;

    public FdcClient(FdcRateLimiter rateLimiter) {
        this.rateLimiter = rateLimiter;
    }

    @PostConstruct
    void init() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        // Without these the default is infinite, which would hang a meal analysis
        // indefinitely whenever FDC is slow.
        factory.setConnectTimeout(Duration.ofMillis(connectTimeoutMs));
        factory.setReadTimeout(Duration.ofMillis(readTimeoutMs));
        this.restTemplate = new RestTemplate(factory);

        if (!isConfigured()) {
            log.info("[FdcClient] No USDA_FDC_API_KEY set — live lookups disabled. "
                    + "Ingredients missing from the embedded table will be reported as unmatched.");
        } else {
            log.info("[FdcClient] Live FoodData Central lookups enabled "
                    + "(connect {}ms, read {}ms, branded={}).",
                    connectTimeoutMs, readTimeoutMs, includeBranded);
        }
    }

    /** True when a key is present and live lookups are switched on. */
    public boolean isConfigured() {
        return enabled && apiKey != null && !apiKey.isBlank();
    }

    /** Result of the boot-time credential probe, surfaced by the diagnostics endpoint. */
    public enum ProbeStatus { NOT_CONFIGURED, OK, INVALID_KEY, UNREACHABLE }

    private volatile ProbeStatus probeStatus = ProbeStatus.NOT_CONFIGURED;

    public ProbeStatus getProbeStatus() { return probeStatus; }

    /**
     * Verifies the credential once at startup against a food that certainly exists.
     *
     * <p>Worth one request out of the hourly budget: without it, a mistyped or unactivated
     * key looks exactly like "this ingredient isn't in the database", and the failure would
     * only show up as quietly degraded nutrition data days later.</p>
     */
    @org.springframework.context.event.EventListener(
            org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void probeOnStartup() {
        if (!isConfigured()) {
            probeStatus = ProbeStatus.NOT_CONFIGURED;
            return;
        }
        try {
            ResponseEntity<String> response = post("cheddar cheese");
            readRateLimitHeaders(response.getHeaders());
            probeStatus = ProbeStatus.OK;
            log.info("[FdcClient] USDA FoodData Central key verified. Quota remaining this hour: {}",
                    rateLimiter.getServerRemaining() < 0 ? "unreported" : rateLimiter.getServerRemaining());
        } catch (HttpStatusCodeException e) {
            int status = e.getStatusCode().value();
            if (status == 401 || status == 403) {
                probeStatus = ProbeStatus.INVALID_KEY;
                log.error("[FdcClient] USDA FoodData Central rejected the API key (HTTP {}). "
                        + "Check USDA_FDC_API_KEY. Live lookups will keep failing until this is fixed; "
                        + "the embedded table still works.", status);
            } else if (status == 429) {
                rateLimiter.recordRateLimited();
                probeStatus = ProbeStatus.OK;
                log.warn("[FdcClient] Key accepted but quota already exhausted at startup.");
            } else {
                probeStatus = ProbeStatus.UNREACHABLE;
                log.warn("[FdcClient] Startup probe failed with HTTP {}.", status);
            }
        } catch (Exception e) {
            probeStatus = ProbeStatus.UNREACHABLE;
            log.warn("[FdcClient] Could not reach USDA FoodData Central at startup: {}. "
                    + "Live lookups will be retried per request.", e.getMessage());
        }
    }

    /**
     * Searches FoodData Central for a food matching {@code query}.
     *
     * @return the best validated match, or empty when nothing trustworthy was found
     */
    public Optional<UsdaFood> search(String query) {
        if (!isConfigured() || query == null || query.isBlank()) {
            return Optional.empty();
        }
        if (!rateLimiter.tryAcquire()) {
            log.debug("[FdcClient] Skipping lookup for '{}' — quota guard engaged.", query);
            return Optional.empty();
        }

        try {
            ResponseEntity<String> response = post(query);
            readRateLimitHeaders(response.getHeaders());

            if (response.getBody() == null) return Optional.empty();
            return pickBest(objectMapper.readTree(response.getBody()), query);

        } catch (HttpStatusCodeException e) {
            if (e.getStatusCode().value() == 429) {
                rateLimiter.recordRateLimited();
            } else {
                log.warn("[FdcClient] Lookup for '{}' failed: HTTP {}", query, e.getStatusCode().value());
            }
            return Optional.empty();
        } catch (Exception e) {
            log.warn("[FdcClient] Lookup for '{}' failed: {}", query, e.getMessage());
            return Optional.empty();
        }
    }

    private ResponseEntity<String> post(String query) {
        List<String> dataTypes = new ArrayList<>(
                List.of("Foundation", "SR Legacy", "Survey (FNDDS)"));
        if (includeBranded) dataTypes.add("Branded");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("query", query);
        // POST with a genuine JSON array: the API documents dataType as an array, and this
        // sidesteps encoding the space in "SR Legacy" into a query string.
        body.put("dataType", dataTypes);
        body.put("pageSize", 5);
        body.put("pageNumber", 1);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        // Header auth rather than a query parameter keeps the key out of URLs,
        // access logs, and any error message that echoes the request.
        headers.set("X-Api-Key", apiKey);

        return restTemplate.exchange(searchEndpoint, HttpMethod.POST,
                new HttpEntity<>(body, headers), String.class);
    }

    private void readRateLimitHeaders(HttpHeaders headers) {
        String remaining = headers.getFirst("X-RateLimit-Remaining");
        if (remaining != null) {
            try {
                rateLimiter.syncRemaining(Integer.parseInt(remaining.trim()));
            } catch (NumberFormatException ignored) {
                // Header shape is not a contract we depend on.
            }
        }
    }

    /**
     * Chooses the best candidate, preferring analytically measured datasets and rejecting
     * anything that fails validation.
     *
     * <p>Relevance ranking alone is not enough. FDC's search happily returns a branded
     * dessert for "salt", and accepting the top hit unconditionally is how a nutrient
     * database ends up less accurate than the model it replaced.</p>
     */
    private Optional<UsdaFood> pickBest(JsonNode root, String query) {
        Set<String> queryTokens = UsdaNutrientRepository.tokenize(query);
        UsdaFood best = null;
        int bestRank = Integer.MAX_VALUE;
        double bestOverlap = 0;

        for (JsonNode node : root.path("foods")) {
            String description = node.path("description").asText("");
            String dataType = node.path("dataType").asText("");

            double overlap = tokenOverlap(queryTokens, description);
            if (overlap < MIN_TOKEN_OVERLAP) continue;

            NutrientProfile profile = parseNutrients(node);
            if (!isPlausible(profile)) continue;

            int rank = DATA_TYPE_PREFERENCE.indexOf(dataType);
            if (rank < 0) rank = DATA_TYPE_PREFERENCE.size();

            if (rank < bestRank || (rank == bestRank && overlap > bestOverlap)) {
                bestRank = rank;
                bestOverlap = overlap;
                best = UsdaFood.builder()
                        .fdcId(node.path("fdcId").isMissingNode() ? null : node.path("fdcId").asInt())
                        .description(description)
                        .aliases(List.of())
                        // FDC records are measured; only our embedded regional entries are estimates.
                        .estimated(false)
                        .per100g(profile)
                        .build();
            }
        }

        if (best == null) {
            log.debug("[FdcClient] No candidate for '{}' passed validation.", query);
        } else {
            log.info("[FdcClient] Resolved '{}' -> '{}' (fdcId {}, overlap {})",
                    query, best.getDescription(), best.getFdcId(), String.format("%.2f", bestOverlap));
        }
        return Optional.ofNullable(best);
    }

    private static double tokenOverlap(Set<String> queryTokens, String description) {
        if (queryTokens.isEmpty()) return 0;
        Set<String> descriptionTokens = UsdaNutrientRepository.tokenize(description);
        long shared = queryTokens.stream().filter(descriptionTokens::contains).count();
        return (double) shared / queryTokens.size();
    }

    /**
     * Rejects records that cannot describe a real food.
     *
     * <p>Guards against incomplete records (every macro zero) and unit mix-ups, where a value
     * reported per serving rather than per 100 g shows up as an impossible energy density.</p>
     */
    private static boolean isPlausible(NutrientProfile p) {
        if (p == null) return false;
        if (p.getKcal() < 0 || p.getKcal() > MAX_PLAUSIBLE_KCAL_PER_100G) return false;
        if (p.getProtein() < 0 || p.getCarbs() < 0 || p.getFat() < 0) return false;
        if (p.getProtein() > 100 || p.getCarbs() > 100 || p.getFat() > 100) return false;

        boolean allMacrosZero = p.getProtein() == 0 && p.getCarbs() == 0 && p.getFat() == 0;
        // Salt and water legitimately have no macros, but then they must have no energy either.
        if (allMacrosZero && p.getKcal() > 5) return false;

        // Energy should roughly track the Atwater sum. A large gap means the record mixes
        // bases or is missing nutrients we are about to treat as zero.
        double atwater = p.atwaterKcal();
        if (p.getKcal() > 20 && atwater > 20) {
            double ratio = p.getKcal() / atwater;
            if (ratio < 0.6 || ratio > 1.7) return false;
        }
        return true;
    }

    private NutrientProfile parseNutrients(JsonNode food) {
        Map<String, Double> byNumber = new HashMap<>();
        for (JsonNode n : food.path("foodNutrients")) {
            String number = n.path("nutrientNumber").asText(null);
            if (number == null || number.isBlank()) continue;
            String unit = n.path("unitName").asText("");
            double value = n.path("value").asDouble(0.0);
            // Energy is published in both kcal and kJ; take only the kcal row.
            if (N_ENERGY.equals(number) && unit.toUpperCase(Locale.ROOT).startsWith("KJ")) continue;
            byNumber.put(number, value);
        }
        if (byNumber.isEmpty()) return null;

        return NutrientProfile.builder()
                .kcal(byNumber.getOrDefault(N_ENERGY, 0.0))
                .protein(byNumber.getOrDefault(N_PROTEIN, 0.0))
                .carbs(byNumber.getOrDefault(N_CARBS, 0.0))
                .fat(byNumber.getOrDefault(N_FAT, 0.0))
                .satFat(byNumber.getOrDefault(N_SATFAT, 0.0))
                .fiber(byNumber.getOrDefault(N_FIBER, 0.0))
                .sugar(byNumber.getOrDefault(N_SUGAR, 0.0))
                .sodium(byNumber.getOrDefault(N_SODIUM, 0.0))
                .potassium(byNumber.getOrDefault(N_POTASSIUM, 0.0))
                .cholesterol(byNumber.getOrDefault(N_CHOLESTEROL, 0.0))
                .build();
    }
}
