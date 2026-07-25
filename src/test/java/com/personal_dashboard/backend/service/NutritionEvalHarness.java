package com.personal_dashboard.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.personal_dashboard.backend.dto.GeminiAnalysisResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.stream.Collectors;

/**
 * Accuracy harness for the two-stage meal-analysis pipeline, measured against the
 * Nutrition5k benchmark (weighed ground truth, CC BY 4.0).
 *
 * <p><b>This costs real money and is disabled by default.</b> It makes two LLM calls per
 * dish, so a 60-dish run is 120 calls. It never runs under a plain {@code ./mvnw test} —
 * it is gated on {@code NUTRITION_EVAL=1}.
 *
 * <pre>
 *   # A/B the two providers over 12 dishes (24 calls — cheap smoke test)
 *   NUTRITION_EVAL=1 EVAL_LIMIT=12 EVAL_PROVIDER=claude \
 *     ./mvnw test -Dtest=NutritionEvalHarness
 *   NUTRITION_EVAL=1 EVAL_LIMIT=12 EVAL_PROVIDER=gemini \
 *     ./mvnw test -Dtest=NutritionEvalHarness
 *
 *   # Full run
 *   NUTRITION_EVAL=1 ./mvnw test -Dtest=NutritionEvalHarness
 * </pre>
 *
 * <p>Environment:
 * <ul>
 *   <li>{@code NUTRITION_EVAL=1} — required, or the test is skipped</li>
 *   <li>{@code EVAL_PROVIDER} — {@code claude} (default) | {@code gemini}</li>
 *   <li>{@code EVAL_LIMIT} — cap the dish count (default: all in the manifest)</li>
 *   <li>{@code ANTHROPIC_API_KEY} / {@code GEMINI_API_KEY} — as the app uses</li>
 *   <li>{@code ANTHROPIC_MODEL} / {@code GEMINI_MODEL} — override the model under test</li>
 * </ul>
 *
 * <p>Deliberately Spring-free: it constructs the providers directly so the harness needs
 * no application context and no MongoDB. It calls the same
 * {@link NutritionPipelineService#analyzeWithTwoStage} the controller calls, so the code
 * path under measurement is the production one.
 *
 * <p>The user profile is passed as {@code null} on purpose — {@code buildStage2Prompt}
 * falls back to neutral clinical defaults, which is the right control when measuring
 * nutrient accuracy rather than personalisation.
 *
 * <p><b>Reading the output.</b> MedAPE (median absolute percentage error) is the headline
 * number, not MAPE: the manifest deliberately includes tiny dishes (an 11 g spinach leaf
 * at 2 kcal) to span the mass range, and percentage error on a 2 kcal denominator is
 * meaningless. Dishes under {@value #MIN_KCAL_FOR_PERCENTAGE} kcal are therefore excluded
 * from percentage metrics but still counted in MAE.
 *
 * <p>Published reference points for total energy: ~36% MAPE for GPT-4o / Claude 3.5
 * Sonnet, 64–110% for Gemini 1.5 Pro. See {@code design/NUTRITION_ACCURACY_PLAN.md} §2.
 */
@EnabledIfEnvironmentVariable(named = "NUTRITION_EVAL", matches = "1")
class NutritionEvalHarness {

    private static final String MANIFEST = "/nutrition-eval/manifest.json";
    private static final String IMAGES = "/nutrition-eval/images/";

    /** Below this, percentage error is dominated by the denominator, not the model. */
    private static final double MIN_KCAL_FOR_PERCENTAGE = 50.0;

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void measurePipelineAccuracy() throws Exception {
        String providerName = env("EVAL_PROVIDER", "claude").toLowerCase(Locale.ROOT);
        VisionProvider provider = buildProvider(providerName);

        // The composition table and resolver are pure and Spring-free, so the harness
        // exercises exactly the production resolution path with no context or database.
        FoodReferenceService foodReference = new FoodReferenceService();
        invokePostConstruct(foodReference);
        NutrientResolver resolver = new NutrientResolver(foodReference);

        // Same provider on both stages so the result is attributable to one model.
        NutritionPipelineService pipeline =
                new NutritionPipelineService(provider, provider, foodReference, resolver);
        invokePostConstruct(pipeline);

        List<Dish> dishes = loadManifest();
        int limit = Integer.parseInt(env("EVAL_LIMIT", String.valueOf(dishes.size())));
        dishes = subsample(dishes, limit);

        System.out.printf("%n=== Nutrition5k eval — provider=%s, dishes=%d ===%n",
                provider.getProviderName(), dishes.size());
        System.out.println("(2 LLM calls per dish; this will take a while)\n");

        List<Result> results = new ArrayList<>();
        for (int i = 0; i < dishes.size(); i++) {
            Dish dish = dishes.get(i);
            System.out.printf("[%d/%d] %s (%.0f g, %.0f kcal) ... ",
                    i + 1, dishes.size(), dish.id, dish.massG, dish.kcal);
            try {
                byte[] image = readImage(dish.id);
                long startedAt = System.currentTimeMillis();
                // No description: image-only is the worst case and the honest baseline.
                GeminiAnalysisResult analysis =
                        pipeline.analyzeWithTwoStage(List.of(new InMemoryMultipartFile(image)), null, null);
                long elapsedMs = System.currentTimeMillis() - startedAt;

                Result r = Result.from(dish, analysis, elapsedMs);
                results.add(r);
                System.out.printf("%.0f kcal (%+.0f%%), %.0f g (%+.0f%%)  %.1fs%n",
                        r.predKcal, pct(r.predKcal, r.dish.kcal),
                        r.predMassG, pct(r.predMassG, r.dish.massG), elapsedMs / 1000.0);
            } catch (Exception e) {
                // A failure is a result: truncated JSON and parse errors are real defects
                // that a "successful" run would hide.
                System.out.printf("FAILED — %s: %s%n",
                        e.getClass().getSimpleName(), truncate(e.getMessage(), 140));
                results.add(Result.failure(dish));
            }
        }

        report(provider.getProviderName(), results);
    }

    // ─── Reporting ────────────────────────────────────────────────────────────

    private void report(String provider, List<Result> results) {
        List<Result> ok = results.stream().filter(r -> !r.failed).toList();
        System.out.printf("%n%n════ RESULTS — %s ════%n", provider);
        System.out.printf("completed %d/%d dishes (%d failed)%n%n",
                ok.size(), results.size(), results.size() - ok.size());

        if (ok.isEmpty()) {
            System.out.println("No successful analyses — nothing to score.");
            return;
        }

        List<Result> scorable = ok.stream()
                .filter(r -> r.dish.kcal >= MIN_KCAL_FOR_PERCENTAGE)
                .toList();

        System.out.printf("%-12s  %8s  %8s  %8s%n", "metric", "MedAPE", "MAPE", "MAE");
        System.out.println("─".repeat(44));
        metric("mass (g)", scorable, r -> r.predMassG, r -> r.dish.massG);
        metric("energy", scorable, r -> r.predKcal, r -> r.dish.kcal);
        metric("protein", scorable, r -> r.predProteinG, r -> r.dish.proteinG);
        metric("carbs", scorable, r -> r.predCarbG, r -> r.dish.carbG);
        metric("fat", scorable, r -> r.predFatG, r -> r.dish.fatG);
        System.out.printf("%n(percentage metrics over %d/%d dishes ≥ %.0f kcal)%n",
                scorable.size(), ok.size(), MIN_KCAL_FOR_PERCENTAGE);

        reportBiasSlope(scorable);
        reportIdentification(ok);
        reportMathGate(ok);

        double avgSeconds = ok.stream().mapToLong(r -> r.elapsedMs).average().orElse(0) / 1000.0;
        System.out.printf("%nmean latency: %.1fs per dish (both stages)%n", avgSeconds);
        System.out.println("\nBaseline this, change one thing, run it again. Record both numbers.");
    }

    private void metric(String label, List<Result> rs,
                        java.util.function.ToDoubleFunction<Result> pred,
                        java.util.function.ToDoubleFunction<Result> truth) {
        List<Double> apes = new ArrayList<>();
        double absErrorSum = 0;
        for (Result r : rs) {
            double t = truth.applyAsDouble(r);
            double p = pred.applyAsDouble(r);
            absErrorSum += Math.abs(p - t);
            if (t > 0) apes.add(Math.abs(p - t) / t * 100.0);
        }
        if (apes.isEmpty()) {
            System.out.printf("%-12s  %8s  %8s  %8s%n", label, "-", "-", "-");
            return;
        }
        System.out.printf("%-12s  %7.1f%%  %7.1f%%  %8.1f%n",
                label, median(apes), mean(apes), absErrorSum / rs.size());
    }

    /**
     * Fits signed relative error against ground-truth mass by least squares.
     * A negative intercept means systematic underestimation; a negative slope means the
     * underestimation grows with portion size. Published slopes run -0.23 to -0.50, and a
     * confirmed slope here is directly correctable with a calibration factor in Java.
     */
    private void reportBiasSlope(List<Result> rs) {
        if (rs.size() < 8) {
            System.out.printf("%nbias slope: need ≥8 scorable dishes (have %d)%n", rs.size());
            return;
        }
        double n = rs.size();
        double meanX = rs.stream().mapToDouble(r -> r.dish.massG).average().orElse(0);
        double meanY = rs.stream().mapToDouble(r -> (r.predKcal - r.dish.kcal) / r.dish.kcal).average().orElse(0);
        double num = 0, den = 0;
        for (Result r : rs) {
            double dx = r.dish.massG - meanX;
            num += dx * ((r.predKcal - r.dish.kcal) / r.dish.kcal - meanY);
            den += dx * dx;
        }
        double slopePer100g = den == 0 ? 0 : (num / den) * 100;
        System.out.printf("%nportion bias: mean relative error %+.1f%% (%s)%n",
                meanY * 100, meanY < 0 ? "UNDERestimating" : "OVERestimating");
        System.out.printf("              slope %+.1f%% per +100 g of true mass%n", slopePer100g);
        if (slopePer100g < -1.0) {
            System.out.println("              → underestimation grows with portion size, as published."
                    + " Calibratable (plan §2.3).");
        }
        System.out.printf("              (n=%.0f)%n", n);
    }

    /** Fraction of ground-truth ingredients the model named, by loose token overlap. */
    private void reportIdentification(List<Result> rs) {
        int found = 0, total = 0, ghosts = 0, predTotal = 0;
        for (Result r : rs) {
            Set<String> predicted = r.predictedNames.stream()
                    .flatMap(s -> tokens(s).stream()).collect(Collectors.toSet());
            for (String truth : r.dish.ingredientNames) {
                total++;
                if (tokens(truth).stream().anyMatch(predicted::contains)) found++;
            }
            Set<String> truthTokens = r.dish.ingredientNames.stream()
                    .flatMap(s -> tokens(s).stream()).collect(Collectors.toSet());
            for (String p : r.predictedNames) {
                predTotal++;
                if (tokens(p).stream().noneMatch(truthTokens::contains)) ghosts++;
            }
        }
        System.out.printf("%nidentification: recall %.0f%% (%d/%d true ingredients named)%n",
                total == 0 ? 0 : 100.0 * found / total, found, total);
        System.out.printf("                %.0f%% of predicted items matched nothing real "
                        + "(%d/%d — includes legitimate hidden-ingredient inferences)%n",
                predTotal == 0 ? 0 : 100.0 * ghosts / predTotal, ghosts, predTotal);
    }

    /** How often the model's self-reported Atwater gate actually holds. */
    private void reportMathGate(List<Result> rs) {
        int claimed = 0, actual = 0;
        for (Result r : rs) {
            if (r.gateClaimedPass) claimed++;
            double expected = r.predProteinG * 4 + r.predCarbG * 4 + r.predFatG * 9;
            if (Math.abs(expected - r.predKcal) <= 2.0) actual++;
        }
        System.out.printf("%nmath gate: model claimed pass %d/%d, actually holds %d/%d%n",
                claimed, rs.size(), actual, rs.size());
        if (claimed > actual) {
            System.out.println("           → self-reported gate is unreliable; enforce it in Java (plan §1.6).");
        }
    }

    /**
     * Takes {@code n} dishes spread evenly across the manifest instead of the first n.
     * The manifest is sorted by mass ascending, so a naive {@code subList(0, n)} would
     * hand back only the smallest dishes — which are exactly the ones whose percentage
     * error is least meaningful. Every-kth keeps the mass range intact at any limit.
     */
    private static List<Dish> subsample(List<Dish> dishes, int n) {
        if (n >= dishes.size() || n <= 0) return dishes;
        List<Dish> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            out.add(dishes.get((int) Math.round(i * (dishes.size() - 1.0) / (n - 1.0))));
        }
        return out;
    }

    // ─── Fixture loading ──────────────────────────────────────────────────────

    private List<Dish> loadManifest() throws Exception {
        JsonNode root;
        try (InputStream in = getClass().getResourceAsStream(MANIFEST)) {
            if (in == null) {
                throw new IllegalStateException(
                        "manifest not found on classpath: " + MANIFEST
                                + "\nRun: python3 src/test/resources/nutrition-eval/build_fixture.py");
            }
            root = mapper.readTree(in);
        }
        List<Dish> dishes = new ArrayList<>();
        for (JsonNode d : root.path("dishes")) {
            Dish dish = new Dish();
            dish.id = d.path("dish_id").asText();
            dish.kcal = d.path("kcal").asDouble();
            dish.massG = d.path("mass_g").asDouble();
            dish.proteinG = d.path("protein_g").asDouble();
            dish.carbG = d.path("carb_g").asDouble();
            dish.fatG = d.path("fat_g").asDouble();
            dish.ingredientNames = new ArrayList<>();
            for (JsonNode ing : d.path("ingredients")) {
                // Seasonings at ~0 g are not identifiable from a photo; scoring recall
                // against them would understate the model unfairly.
                if (ing.path("grams").asDouble() >= 1.0) {
                    dish.ingredientNames.add(ing.path("name").asText());
                }
            }
            dishes.add(dish);
        }
        return dishes;
    }

    private byte[] readImage(String dishId) throws Exception {
        String resource = IMAGES + dishId + ".png";
        try (InputStream in = getClass().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("image missing: " + resource
                        + "\nRun: python3 src/test/resources/nutrition-eval/build_fixture.py");
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            in.transferTo(out);
            return out.toByteArray();
        }
    }

    // ─── Provider wiring (no Spring context) ──────────────────────────────────

    private VisionProvider buildProvider(String name) throws Exception {
        return switch (name) {
            case "claude" -> {
                ClaudeVisionProvider p = new ClaudeVisionProvider();
                set(p, "apiKey", required("ANTHROPIC_API_KEY"));
                set(p, "model", env("ANTHROPIC_MODEL", "claude-sonnet-5"));
                invokePostConstruct(p);
                yield p;
            }
            case "gemini" -> {
                GeminiVisionProvider p = new GeminiVisionProvider();
                set(p, "apiKey", required("GEMINI_API_KEY"));
                String model = env("GEMINI_MODEL", "gemini-3.6-flash");
                set(p, "model", model);
                set(p, "endpoint", env("GEMINI_API_ENDPOINT",
                        "https://generativelanguage.googleapis.com/v1beta/models/"
                                + model + ":generateContent"));
                invokePostConstruct(p);
                yield p;
            }
            default -> throw new IllegalArgumentException(
                    "EVAL_PROVIDER must be 'claude' or 'gemini', got: " + name);
        };
    }

    private static void set(Object target, String fieldName, String value) throws Exception {
        Field f = target.getClass().getDeclaredField(fieldName);
        f.setAccessible(true);
        f.set(target, value);
    }

    /** Providers and the pipeline initialise their clients in a private @PostConstruct. */
    private static void invokePostConstruct(Object target) throws Exception {
        for (var m : target.getClass().getDeclaredMethods()) {
            if (m.isAnnotationPresent(jakarta.annotation.PostConstruct.class)) {
                m.setAccessible(true);
                m.invoke(target);
            }
        }
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Resolves a setting from the environment, falling back to the gitignored
     * {@code application-local.properties} — which stores keys under the same
     * env-var-style names ({@code ANTHROPIC_API_KEY}, {@code GEMINI_API_KEY}), so running
     * the harness needs no extra export beyond what the app already needs.
     */
    private static String env(String key, String fallback) {
        String v = System.getenv(key);
        if (v == null || v.isBlank()) v = localProperties().getProperty(key);
        return (v == null || v.isBlank()) ? fallback : v;
    }

    private static String required(String key) {
        String v = env(key, null);
        if (v == null) {
            throw new IllegalStateException(key + " not found in the environment or in "
                    + "src/main/resources/application-local.properties — the harness needs a real API key");
        }
        return v;
    }

    private static java.util.Properties cachedLocalProperties;

    private static java.util.Properties localProperties() {
        if (cachedLocalProperties != null) return cachedLocalProperties;
        java.util.Properties p = new java.util.Properties();
        try (InputStream in = NutritionEvalHarness.class
                .getResourceAsStream("/application-local.properties")) {
            if (in != null) p.load(in);
        } catch (Exception ignored) {
            // Absent or unreadable is fine — env vars are the primary source.
        }
        cachedLocalProperties = p;
        return p;
    }

    private static double pct(double pred, double truth) {
        return truth == 0 ? 0 : (pred - truth) / truth * 100.0;
    }

    private static double mean(List<Double> xs) {
        return xs.stream().mapToDouble(Double::doubleValue).average().orElse(0);
    }

    private static double median(List<Double> xs) {
        List<Double> s = xs.stream().sorted().toList();
        int n = s.size();
        return n % 2 == 1 ? s.get(n / 2) : (s.get(n / 2 - 1) + s.get(n / 2)) / 2.0;
    }

    /** Lowercase alphabetic tokens ≥4 chars — loose enough to match "white rice" ↔ "rice, white". */
    private static Set<String> tokens(String s) {
        Set<String> out = new HashSet<>();
        if (s == null) return out;
        for (String t : s.toLowerCase(Locale.ROOT).split("[^a-z]+")) {
            if (t.length() >= 4) out.add(t);
        }
        return out;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "null";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    // ─── Value types ──────────────────────────────────────────────────────────

    private static final class Dish {
        String id;
        double kcal, massG, proteinG, carbG, fatG;
        List<String> ingredientNames;
    }

    private static final class Result {
        Dish dish;
        boolean failed;
        double predKcal, predMassG, predProteinG, predCarbG, predFatG;
        boolean gateClaimedPass;
        long elapsedMs;
        List<String> predictedNames = new ArrayList<>();

        static Result failure(Dish dish) {
            Result r = new Result();
            r.dish = dish;
            r.failed = true;
            return r;
        }

        static Result from(Dish dish, GeminiAnalysisResult a, long elapsedMs) {
            Result r = new Result();
            r.dish = dish;
            r.elapsedMs = elapsedMs;
            GeminiAnalysisResult.MacroTotals t = a.getMacroTotals();
            if (t != null) {
                r.predKcal = t.getCaloriesKcal();
                r.predProteinG = t.getProteinG();
                r.predCarbG = t.getCarbohydratesG();
                r.predFatG = t.getFatG();
                if (t.getMathVerification() != null) {
                    r.gateClaimedPass = t.getMathVerification().isGatePassed();
                }
            }
            if (a.getIngredientsBreakdown() != null) {
                for (GeminiAnalysisResult.IngredientBreakdown ing : a.getIngredientsBreakdown()) {
                    r.predMassG += ing.getEstimatedWeightG();
                    String name = ing.getCommonName() != null ? ing.getCommonName() : ing.getName();
                    if (name != null) r.predictedNames.add(name);
                }
            }
            return r;
        }
    }

    /**
     * Minimal MultipartFile over a byte[]. The pipeline only ever calls isEmpty() and
     * getBytes(); everything else throws so a future change that depends on more than
     * that fails loudly instead of silently reading nulls.
     */
    private static final class InMemoryMultipartFile
            implements org.springframework.web.multipart.MultipartFile {
        private final byte[] bytes;

        InMemoryMultipartFile(byte[] bytes) {
            this.bytes = bytes;
        }

        @Override public String getName() { return "files"; }
        @Override public String getOriginalFilename() { return "dish.png"; }
        @Override public String getContentType() { return "image/png"; }
        @Override public boolean isEmpty() { return bytes.length == 0; }
        @Override public long getSize() { return bytes.length; }
        @Override public byte[] getBytes() { return bytes; }
        @Override public InputStream getInputStream() { return new java.io.ByteArrayInputStream(bytes); }
        @Override public void transferTo(java.io.File dest) {
            throw new UnsupportedOperationException("eval harness is in-memory only");
        }
    }
}
