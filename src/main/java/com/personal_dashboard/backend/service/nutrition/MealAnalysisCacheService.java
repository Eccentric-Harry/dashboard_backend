package com.personal_dashboard.backend.service.nutrition;

import com.personal_dashboard.backend.model.MealAnalysisCacheEntry;
import com.personal_dashboard.backend.repository.MealAnalysisCacheRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Content-addressed cache in front of the two paid pipeline stages.
 *
 * <p>Every cache hit is one fewer Gemini call. Because the key is a hash of the actual inputs,
 * a hit is byte-identical to what the API would have returned, so this trades no accuracy for
 * the saving.</p>
 */
@Service
@Slf4j
public class MealAnalysisCacheService {

    public static final String KIND_EXTRACTION = "EXTRACTION";
    public static final String KIND_NARRATIVE = "NARRATIVE";

    @Value("${nutrition.cache.enabled:true}")
    private boolean enabled;

    private final MealAnalysisCacheRepository repository;

    public MealAnalysisCacheService(MealAnalysisCacheRepository repository) {
        this.repository = repository;
    }

    /**
     * Key for the vision stage: the image bytes and the user's description fully determine
     * the ingredient list, so this key deliberately excludes the user profile and is shared
     * across all users.
     */
    public String extractionKey(byte[] imageBytes, String description) {
        return hash(KIND_EXTRACTION,
                imageBytes == null ? "no-image" : hash("img", imageBytes),
                UsdaNutrientRepository.normalize(description));
    }

    /**
     * Key for the narrative stage. Includes the profile signature — the same plate warrants
     * different advice for a user with diabetes than for one without — but excludes the exact
     * remaining budget, which is recomputed deterministically and injected after the fact.
     */
    public String narrativeKey(String extractionJson, NutritionContext ctx, String flagSignature) {
        String profile = String.join("|",
                String.valueOf(ctx.getPrimaryGoal()),
                String.join(",", ctx.getMedicalConditions()),
                String.format("%.0f/%.0f/%.0f/%.0f",
                        ctx.getGoalCalories(), ctx.getGoalProteinG(),
                        ctx.getGoalCarbsG(), ctx.getGoalFatG()));
        return hash(KIND_NARRATIVE, extractionJson, profile, flagSignature);
    }

    /** Returns the cached payload for a key, incrementing its hit counter. */
    public Optional<String> get(String cacheKey) {
        if (!enabled) return Optional.empty();
        try {
            Optional<MealAnalysisCacheEntry> found = repository.findByCacheKey(cacheKey);
            found.ifPresent(e -> {
                e.setHitCount(e.getHitCount() + 1);
                repository.save(e);
                log.info("[MealAnalysisCache] HIT kind={} hits={}", e.getKind(), e.getHitCount());
            });
            return found.map(MealAnalysisCacheEntry::getPayload);
        } catch (Exception e) {
            log.warn("[MealAnalysisCache] Read failed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /** Stores a stage output. Failures are logged and swallowed — caching is never load-bearing. */
    public void put(String kind, String cacheKey, String payload) {
        if (!enabled || payload == null || payload.isBlank()) return;
        try {
            repository.save(MealAnalysisCacheEntry.builder()
                    .kind(kind)
                    .cacheKey(cacheKey)
                    .payload(payload)
                    .cachedAt(Instant.now())
                    .build());
        } catch (Exception e) {
            log.warn("[MealAnalysisCache] Write failed: {}", e.getMessage());
        }
    }

    /** ASCII unit separator: cannot occur in any input, so field boundaries stay unambiguous. */
    private static final String FIELD_SEPARATOR = "\u001F";

    private static String hash(String... parts) {
        return hash(String.join(FIELD_SEPARATOR, parts).getBytes(StandardCharsets.UTF_8));
    }

    private static String hash(String prefix, byte[] bytes) {
        MessageDigest md = digest();
        md.update(prefix.getBytes(StandardCharsets.UTF_8));
        md.update(bytes);
        return HexFormat.of().formatHex(md.digest());
    }

    private static String hash(byte[] bytes) {
        return HexFormat.of().formatHex(digest().digest(bytes));
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
