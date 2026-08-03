package com.personal_dashboard.backend.controller;

import com.personal_dashboard.backend.repository.MealAnalysisCacheRepository;
import com.personal_dashboard.backend.repository.NutrientCacheRepository;
import com.personal_dashboard.backend.service.nutrition.FdcClient;
import com.personal_dashboard.backend.service.nutrition.FdcRateLimiter;
import com.personal_dashboard.backend.service.nutrition.UsdaNutrientRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Read-only health view of the nutrition pipeline's data sources.
 *
 * <p>Exists so that "is the USDA key actually working?" and "how much of the quota is left?"
 * are answerable without reading logs — the two questions that come up immediately after
 * deploying a new key.</p>
 *
 * <p>Deliberately reports no key material, only whether one is present and whether it worked.</p>
 */
@RestController
@RequestMapping("/api/v1/admin/nutrition")
@RequiredArgsConstructor
@Tag(name = "Nutrition Diagnostics", description = "Status of nutrient data sources and caches")
public class NutritionDiagnosticsController {

    private final FdcClient fdcClient;
    private final FdcRateLimiter rateLimiter;
    private final UsdaNutrientRepository nutrientRepository;
    private final NutrientCacheRepository nutrientCacheRepository;
    private final MealAnalysisCacheRepository mealAnalysisCacheRepository;

    @GetMapping("/status")
    @Operation(summary = "Nutrient data source status",
            description = "Reports USDA key validity, remaining hourly quota, embedded table size, "
                    + "and cache occupancy. Contains no secrets.")
    public ResponseEntity<Map<String, Object>> status() {
        Map<String, Object> usda = new LinkedHashMap<>();
        usda.put("apiKeyConfigured", fdcClient.isConfigured());
        usda.put("startupProbe", fdcClient.getProbeStatus().name());
        usda.put("embeddedFoods", nutrientRepository.getEmbeddedFoodCount());
        usda.put("embeddedLookupKeys", nutrientRepository.getLookupKeyCount());

        Map<String, Object> quota = new LinkedHashMap<>();
        quota.put("hourlyBudget", rateLimiter.getHourlyBudget());
        quota.put("usedThisWindow", rateLimiter.getUsedThisWindow());
        quota.put("serverReportedRemaining",
                rateLimiter.getServerRemaining() < 0 ? null : rateLimiter.getServerRemaining());
        quota.put("exhausted", rateLimiter.isExhausted());
        quota.put("blockedUntil", rateLimiter.getBlockedUntil().isAfter(java.time.Instant.EPOCH)
                ? rateLimiter.getBlockedUntil().toString() : null);

        Map<String, Object> caches = new LinkedHashMap<>();
        caches.put("nutrientCacheEntries", countOrNull(nutrientCacheRepository::count));
        caches.put("mealAnalysisCacheEntries", countOrNull(mealAnalysisCacheRepository::count));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("usda", usda);
        body.put("quota", quota);
        body.put("caches", caches);
        return ResponseEntity.ok(body);
    }

    /** A diagnostics endpoint must not fail just because a datastore is briefly unavailable. */
    private static Long countOrNull(java.util.function.Supplier<Long> supplier) {
        try {
            return supplier.get();
        } catch (Exception e) {
            return null;
        }
    }
}
