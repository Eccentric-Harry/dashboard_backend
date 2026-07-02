package com.personal_dashboard.backend.controller;

import com.personal_dashboard.backend.dto.ApiMeta;
import com.personal_dashboard.backend.dto.ApiResponse;
import com.personal_dashboard.backend.dto.GeminiAnalysisResult;
import com.personal_dashboard.backend.dto.MealAnalysisResponse;
import com.personal_dashboard.backend.model.MealEntry;
import com.personal_dashboard.backend.model.UserAccount;
import com.personal_dashboard.backend.repository.UserAccountRepository;
import com.personal_dashboard.backend.security.UserContext;
import com.personal_dashboard.backend.service.DailyFoodLogService;
import com.personal_dashboard.backend.service.GeminiNutritionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * REST controller for the automated Gemini meal analysis pipeline.
 *
 * POST /api/v1/meals/analyze
 *   Accepts an optional image file and/or text description, orchestrates the
 *   two-stage Gemini pipeline, persists the enriched MealEntry to MongoDB,
 *   and returns the full analysis result in a single call.
 */
@RestController
@RequestMapping("/api/v1/meals")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Meal Analysis", description = "AI-powered two-stage meal nutrition pipeline via Gemini")
public class MealAnalysisController {

    private final GeminiNutritionService geminiNutritionService;
    private final DailyFoodLogService dailyFoodLogService;
    private final UserAccountRepository userAccountRepository;

    /**
     * Analyze a meal using the Gemini AI pipeline, persist, and return the full analysis.
     *
     * @param file        Optional image file (JPG, PNG, WEBP, HEIC — max 10MB)
     * @param description Optional text description of the meal
     * @param mealType    Required: Breakfast | Lunch | Dinner | Snack | Midnight | Post Workout | Mid-Morning
     * @param date        Optional: YYYY-MM-DD (defaults to today)
     */
    @PostMapping(value = "/analyze", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(
            summary = "AI Meal Analysis",
            description = "Runs a two-stage Gemini pipeline: Stage 1 identifies food items from image/text, Stage 2 calculates full nutrition and medical analysis. Persists to MongoDB and returns the enriched result.")
    public ResponseEntity<ApiResponse<MealAnalysisResponse>> analyzeMeal(
            @RequestPart(value = "file", required = false) MultipartFile file,
            @RequestParam(value = "description", required = false) String description,
            @RequestParam("mealType") String mealType,
            @RequestParam(value = "date", required = false) String date) {

        // ── Validation ──────────────────────────────────────────────────
        boolean hasImage = file != null && !file.isEmpty();
        boolean hasText = description != null && !description.isBlank();

        if (!hasImage && !hasText) {
            return ResponseEntity.badRequest().body(ApiResponse.<MealAnalysisResponse>builder()
                    .meta(buildMeta("validation-error"))
                    .build());
        }

        String validatedMealType = validateMealType(mealType);
        if (validatedMealType == null) {
            return ResponseEntity.badRequest().body(ApiResponse.<MealAnalysisResponse>builder()
                    .meta(buildMeta("validation-error"))
                    .build());
        }

        String targetDate = (date != null && !date.isBlank()) ? date : LocalDate.now().toString();

        // ── Load User Profile ────────────────────────────────────────────
        String userId = UserContext.getRequiredUserId();
        UserAccount userProfile = userAccountRepository.findById(userId).orElse(null);

        // ── Run Two-Stage Gemini Pipeline ────────────────────────────────
        GeminiAnalysisResult analysis;
        try {
            analysis = geminiNutritionService.analyzeWithTwoStage(
                    hasImage ? file : null,
                    hasText ? description : null,
                    userProfile);
        } catch (Exception e) {
            log.error("[MealAnalysis] Gemini pipeline failed for user={}: {}", userId, e.getMessage(), e);
            return ResponseEntity.internalServerError().body(ApiResponse.<MealAnalysisResponse>builder()
                    .meta(buildMeta("gemini-error"))
                    .build());
        }

        // ── Map Gemini Output → MealEntry ───────────────────────────────
        GeminiAnalysisResult.MealTotals totals = analysis.getMealTotals();
        GeminiAnalysisResult.OverallAssessment assessment = analysis.getOverallAssessment();

        // Build description from identified items
        String mealDescription = buildDescription(analysis, description);

        // Map mealItems to List<Map<String,Object>> for MealEntry.mealItems
        List<Map<String, Object>> mealItemsMaps = mapMealItems(analysis.getMealItems());

        // Map medical analysis into the existing acneImpactAssessment / recompositionAssessment fields
        // for backward compat with MealDetailsModal, while also storing new fields
        Map<String, Object> medicalAnalysisMap = buildMedicalAnalysisMap(analysis);
        Map<String, Object> overallAssessmentMap = buildOverallAssessmentMap(assessment);
        Map<String, Object> dailyTargetProgressMap = buildDailyTargetMap(analysis.getDailyTargetProgress());

        int calories = totals != null ? (int) Math.round(totals.getCalories()) : 0;
        int protein = totals != null ? (int) Math.round(totals.getProtein()) : 0;

        MealEntry entry = MealEntry.builder()
                .description(mealDescription)
                .calories(calories)
                .proteinGrams(protein)
                .mealQuality(assessment != null ? assessment.getMealQuality() : null)
                .timestamp(Instant.now())
                .mealItems(mealItemsMaps)
                .totalSummary(buildTotalSummaryMap(totals))
                .recompositionAssessment(overallAssessmentMap)
                .acneImpactAssessment(medicalAnalysisMap)
                .dailyContext(dailyTargetProgressMap)
                .build();

        // ── Persist to MongoDB ───────────────────────────────────────────
        String savedEntryId;
        try {
            var savedLog = dailyFoodLogService.addMeal(targetDate, validatedMealType, entry);
            // Find the entry we just added (it's the last one in its mealType list)
            savedEntryId = entry.getId() != null ? entry.getId() : "unknown";
        } catch (Exception e) {
            log.error("[MealAnalysis] Persistence failed for user={}: {}", userId, e.getMessage(), e);
            return ResponseEntity.internalServerError().body(ApiResponse.<MealAnalysisResponse>builder()
                    .meta(buildMeta("persistence-error"))
                    .build());
        }

        // ── Build Response ───────────────────────────────────────────────
        MealAnalysisResponse response = MealAnalysisResponse.builder()
                .mealEntryId(savedEntryId)
                .mealType(validatedMealType)
                .date(targetDate)
                .description(mealDescription)
                .calories(calories)
                .proteinGrams(protein)
                .analysis(analysis)
                .build();

        return ResponseEntity.ok(ApiResponse.<MealAnalysisResponse>builder()
                .data(response)
                .meta(buildMeta("ai-analyze"))
                .build());
    }

    // ─── Private Helpers ──────────────────────────────────────────────

    private String validateMealType(String mealType) {
        if (mealType == null) return null;
        String[] valid = {"Breakfast", "Lunch", "Dinner", "Snack", "Midnight", "Post Workout", "Mid-Morning"};
        for (String v : valid) {
            if (v.equalsIgnoreCase(mealType.trim())) return v;
        }
        return null;
    }

    private String buildDescription(GeminiAnalysisResult analysis, String fallbackDescription) {
        if (analysis.getMealItems() != null && !analysis.getMealItems().isEmpty()) {
            return analysis.getMealItems().stream()
                    .map(GeminiAnalysisResult.MealItemResult::getName)
                    .filter(Objects::nonNull)
                    .limit(4)
                    .collect(Collectors.joining(" + "));
        }
        return fallbackDescription != null ? fallbackDescription : "AI Analysed Meal";
    }

    private List<Map<String, Object>> mapMealItems(List<GeminiAnalysisResult.MealItemResult> items) {
        if (items == null) return Collections.emptyList();
        return items.stream().map(item -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", item.getName());
            m.put("serving_size", item.getServingSize());
            m.put("confidence", item.getConfidence());
            m.put("calories", item.getCalories());
            m.put("protein", item.getProtein());
            m.put("carbs", item.getCarbs());
            m.put("fat", item.getFat());
            m.put("fiber", item.getFiber());
            m.put("sugar", item.getSugar());
            m.put("sodium", item.getSodium());
            m.put("saturated_fat", item.getSaturatedFat());
            return m;
        }).collect(Collectors.toList());
    }

    private Map<String, Object> buildTotalSummaryMap(GeminiAnalysisResult.MealTotals totals) {
        if (totals == null) return Collections.emptyMap();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("calories_kcal", totals.getCalories());
        m.put("protein_g", totals.getProtein());
        m.put("carbs_g", totals.getCarbs());
        m.put("fat_g", totals.getFat());
        m.put("fiber_g", totals.getFiber());
        m.put("sugar_g", totals.getSugar());
        m.put("sodium_mg", totals.getSodium());
        return m;
    }

    private Map<String, Object> buildMedicalAnalysisMap(GeminiAnalysisResult analysis) {
        if (analysis.getMedicalAnalysis() == null || analysis.getMedicalAnalysis().isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("medical_analysis", analysis.getMedicalAnalysis().stream().map(item -> {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("condition", item.getCondition());
            entry.put("risk", item.getRisk());
            entry.put("findings", item.getFindings());
            entry.put("recommendations", item.getRecommendations());
            return entry;
        }).collect(Collectors.toList()));
        return m;
    }

    private Map<String, Object> buildOverallAssessmentMap(GeminiAnalysisResult.OverallAssessment a) {
        if (a == null) return Collections.emptyMap();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("meal_quality", a.getMealQuality());
        m.put("fitness_alignment", a.getFitnessAlignment());
        m.put("strengths", a.getStrengths());
        m.put("concerns", a.getConcerns());
        m.put("improvements", a.getImprovements());
        return m;
    }

    private Map<String, Object> buildDailyTargetMap(GeminiAnalysisResult.DailyTargetProgress p) {
        if (p == null) return Collections.emptyMap();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("calories_pct", p.getCaloriesPct());
        m.put("protein_pct", p.getProteinPct());
        m.put("carbs_pct", p.getCarbsPct());
        m.put("fat_pct", p.getFatPct());
        m.put("fiber_pct", p.getFiberPct());
        return m;
    }

    private ApiMeta buildMeta(String action) {
        return ApiMeta.builder()
                .requestId(UUID.randomUUID().toString())
                .timestamp(Instant.now().toString())
                .source("meal-analysis-" + action)
                .build();
    }
}
