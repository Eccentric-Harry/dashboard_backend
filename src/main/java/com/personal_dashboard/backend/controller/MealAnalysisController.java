package com.personal_dashboard.backend.controller;

import com.personal_dashboard.backend.dto.ApiMeta;
import com.personal_dashboard.backend.dto.ApiResponse;
import com.personal_dashboard.backend.dto.GeminiAnalysisResult;
import com.personal_dashboard.backend.dto.MealAnalysisResponse;
import com.personal_dashboard.backend.model.DailyFoodLog;
import com.personal_dashboard.backend.model.MealEntry;
import com.personal_dashboard.backend.model.UserAccount;
import com.personal_dashboard.backend.repository.UserAccountRepository;
import com.personal_dashboard.backend.security.UserContext;
import com.personal_dashboard.backend.service.DailyFoodLogService;
import com.personal_dashboard.backend.service.NutritionPipelineService;
import com.personal_dashboard.backend.service.nutrition.NutritionContext;
import com.personal_dashboard.backend.service.nutrition.NutritionContextFactory;
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

    private final NutritionPipelineService nutritionPipelineService;
    private final DailyFoodLogService dailyFoodLogService;
    private final UserAccountRepository userAccountRepository;
    private final NutritionContextFactory nutritionContextFactory;

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

        // ── Load User Profile and today's real intake ────────────────────
        String userId = UserContext.getRequiredUserId();
        UserAccount userProfile = userAccountRepository.findById(userId).orElse(null);

        // Budget analysis is only meaningful against what has actually been eaten today.
        DailyFoodLog todayLog = null;
        try {
            todayLog = dailyFoodLogService.getDailyLog(targetDate);
        } catch (Exception e) {
            log.warn("[MealAnalysis] Could not load today's log for user={}; "
                    + "budget will be computed against a full day: {}", userId, e.getMessage());
        }
        NutritionContext context = nutritionContextFactory.build(userProfile, todayLog);

        // ── Run the analysis pipeline ────────────────────────────────────
        GeminiAnalysisResult analysis;
        try {
            analysis = nutritionPipelineService.analyze(
                    hasImage ? file : null,
                    hasText ? description : null,
                    context);
        } catch (Exception e) {
            log.error("[MealAnalysis] Gemini pipeline failed for user={}: {}", userId, e.getMessage(), e);
            return ResponseEntity.internalServerError().body(ApiResponse.<MealAnalysisResponse>builder()
                    .meta(buildMeta("gemini-error"))
                    .build());
        }

        // ── Map Gemini Output → MealEntry ───────────────────────────────
        GeminiAnalysisResult.MacroTotals totals = analysis.getMacroTotals();
        GeminiAnalysisResult.MealScore score = analysis.getMealScore();

        // Build description from identified items
        String mealDescription = buildDescription(analysis, description);

        // Map ingredients to List<Map<String,Object>> for MealEntry.mealItems
        List<Map<String, Object>> mealItemsMaps = mapIngredients(analysis.getIngredientsBreakdown());

        // Map clinical flags and medical analysis
        Map<String, Object> medicalAnalysisMap = buildMedicalAnalysisMap(analysis);
        Map<String, Object> overallAssessmentMap = buildOverallAssessmentMap(analysis);
        Map<String, Object> dailyTargetProgressMap = buildDailyTargetMap(analysis.getDailyBudgetAnalysis());

        int calories = totals != null ? (int) Math.round(totals.getCaloriesKcal()) : 0;
        int protein = totals != null ? (int) Math.round(totals.getProteinG()) : 0;

        MealEntry entry = MealEntry.builder()
                .description(mealDescription)
                .calories(calories)
                .proteinGrams(protein)
                .mealQuality(score != null ? score.getLetterGrade() : null)
                .timestamp(Instant.now())
                .mealItems(mealItemsMaps)
                .totalSummary(buildTotalSummaryMap(totals))
                .recompositionAssessment(overallAssessmentMap)
                .acneImpactAssessment(medicalAnalysisMap)
                .healthAnalysis(medicalAnalysisMap)
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
        // Use meal_label from the analysis if available
        if (analysis.getMealLabel() != null && !analysis.getMealLabel().isBlank()) {
            return analysis.getMealLabel();
        }
        // Fallback: build from ingredient common names
        if (analysis.getIngredientsBreakdown() != null && !analysis.getIngredientsBreakdown().isEmpty()) {
            return analysis.getIngredientsBreakdown().stream()
                    .map(i -> i.getCommonName() != null ? i.getCommonName() : i.getName())
                    .filter(Objects::nonNull)
                    .limit(4)
                    .collect(Collectors.joining(" + "));
        }
        return fallbackDescription != null ? fallbackDescription : "AI Analysed Meal";
    }

    private List<Map<String, Object>> mapIngredients(List<GeminiAnalysisResult.IngredientBreakdown> items) {
        if (items == null) return Collections.emptyList();
        return items.stream().map(item -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", item.getCommonName() != null ? item.getCommonName() : item.getName());
            m.put("serving_size", item.getEstimatedWeightG() + "g");
            m.put("confidence", "high");  // New schema uses item_math_check instead
            if (item.getNutrients() != null) {
                m.put("calories", item.getNutrients().getCaloriesKcal());
                m.put("protein", item.getNutrients().getProteinG());
                m.put("carbs", item.getNutrients().getCarbohydratesG());
                m.put("fat", item.getNutrients().getFatG());
                m.put("fiber", item.getNutrients().getDietaryFiberG());
                m.put("sugar", item.getNutrients().getSugarG());
                m.put("sodium", item.getNutrients().getSodiumMg());
                m.put("saturated_fat", item.getNutrients().getSaturatedFatG());
            }
            m.put("is_hidden", item.isHidden());
            m.put("clinical_item_flags", item.getClinicalItemFlags());
            return m;
        }).collect(Collectors.toList());
    }

    private Map<String, Object> buildTotalSummaryMap(GeminiAnalysisResult.MacroTotals totals) {
        if (totals == null) return Collections.emptyMap();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("calories_kcal", totals.getCaloriesKcal());
        m.put("protein_g", totals.getProteinG());
        m.put("carbs_g", totals.getCarbohydratesG());
        m.put("fat_g", totals.getFatG());
        m.put("fiber_g", totals.getDietaryFiberG());
        m.put("sugar_g", totals.getSugarG());
        m.put("sodium_mg", totals.getSodiumMg());
        m.put("saturated_fat_g", totals.getSaturatedFatG());
        m.put("cholesterol_mg", totals.getCholesterolMg());
        m.put("potassium_mg", totals.getPotassiumMg());
        return m;
    }

    private Map<String, Object> buildMedicalAnalysisMap(GeminiAnalysisResult analysis) {
        Map<String, Object> m = new LinkedHashMap<>();

        // Map clinical_flags to the medical_analysis format for backward compat
        if (analysis.getClinicalFlags() != null && !analysis.getClinicalFlags().isEmpty()) {
            // Group clinical flags by condition_link to create per-condition entries
            Map<String, List<GeminiAnalysisResult.ClinicalFlag>> byCondition =
                    analysis.getClinicalFlags().stream()
                            .collect(Collectors.groupingBy(f ->
                                    f.getConditionLink() != null ? f.getConditionLink() : "General"));

            List<Map<String, Object>> medicalAnalysis = byCondition.entrySet().stream().map(entry -> {
                Map<String, Object> conditionMap = new LinkedHashMap<>();
                conditionMap.put("condition", entry.getKey());

                // Determine highest severity in this group
                String maxSeverity = entry.getValue().stream()
                        .map(GeminiAnalysisResult.ClinicalFlag::getSeverity)
                        .map(s -> s != null ? s.toLowerCase() : "low")
                        .reduce((a, b) -> severityRank(a) > severityRank(b) ? a : b)
                        .orElse("low");
                conditionMap.put("risk", maxSeverity);

                conditionMap.put("findings", entry.getValue().stream()
                        .map(f -> f.getTitle() + ": " + f.getMechanisticPathway())
                        .collect(Collectors.toList()));

                // Pull recommendations from the analysis recommendations list
                List<String> recs = analysis.getRecommendations() != null
                        ? analysis.getRecommendations().stream()
                        .filter(r -> entry.getKey().equalsIgnoreCase(r.getConditionTargeted())
                                || "General Wellness".equalsIgnoreCase(r.getConditionTargeted()))
                        .map(r -> r.getAction())
                        .collect(Collectors.toList())
                        : Collections.emptyList();
                conditionMap.put("recommendations", recs);

                return conditionMap;
            }).collect(Collectors.toList());

            m.put("medical_analysis", medicalAnalysis);
        }

        // Include glycaemic assessment
        if (analysis.getGlycaemicAssessment() != null) {
            Map<String, Object> glycaemic = new LinkedHashMap<>();
            glycaemic.put("total_meal_glycaemic_load", analysis.getGlycaemicAssessment().getTotalMealGlycaemicLoad());
            glycaemic.put("gl_classification", analysis.getGlycaemicAssessment().getGlClassification());
            glycaemic.put("insulin_impact_summary", analysis.getGlycaemicAssessment().getInsulinImpactSummary());
            m.put("glycaemic_assessment", glycaemic);
        }

        return m;
    }

    private Map<String, Object> buildOverallAssessmentMap(GeminiAnalysisResult analysis) {
        Map<String, Object> m = new LinkedHashMap<>();

        GeminiAnalysisResult.MealScore score = analysis.getMealScore();
        if (score != null) {
            // Map letter_grade to meal_quality for backward compat
            String quality = switch (score.getLetterGrade() != null ? score.getLetterGrade() : "C") {
                case "A" -> "excellent";
                case "B" -> "good";
                case "C" -> "fair";
                default -> "poor";
            };
            m.put("meal_quality", quality);
            m.put("letter_grade", score.getLetterGrade());
            m.put("overall_score", score.getOverallScore());
            m.put("score_rationale", score.getScoreRationale());
            m.put("fitness_alignment", score.getScoreRationale());
        }

        // Strengths from positive_highlights
        if (analysis.getPositiveHighlights() != null) {
            m.put("strengths", analysis.getPositiveHighlights().stream()
                    .map(h -> h.getIngredientOrAspect() + ": " + h.getBenefit())
                    .collect(Collectors.toList()));
        }

        // Concerns from clinical_flags
        if (analysis.getClinicalFlags() != null) {
            m.put("concerns", analysis.getClinicalFlags().stream()
                    .map(GeminiAnalysisResult.ClinicalFlag::getTitle)
                    .collect(Collectors.toList()));
        }

        // Improvements from recommendations
        if (analysis.getRecommendations() != null) {
            m.put("improvements", analysis.getRecommendations().stream()
                    .map(r -> r.getTitle() + ": " + r.getAction())
                    .collect(Collectors.toList()));
        }

        return m;
    }

    private Map<String, Object> buildDailyTargetMap(GeminiAnalysisResult.DailyBudgetAnalysis budget) {
        if (budget == null) return Collections.emptyMap();
        Map<String, Object> m = new LinkedHashMap<>();

        if (budget.getPercentageOfDailyGoalsThisMeal() != null) {
            GeminiAnalysisResult.DailyGoalPercentages pct = budget.getPercentageOfDailyGoalsThisMeal();
            m.put("calories_pct", pct.getCaloriesPct());
            m.put("protein_pct", pct.getProteinPct());
            m.put("carbs_pct", pct.getCarbsPct());
            m.put("fat_pct", pct.getFatPct());
            m.put("sodium_pct", pct.getSodiumPct());
        }

        if (budget.getBudgetStatus() != null) {
            Map<String, Object> status = new LinkedHashMap<>();
            status.put("calories", budget.getBudgetStatus().getCalories());
            status.put("protein", budget.getBudgetStatus().getProtein());
            status.put("carbs", budget.getBudgetStatus().getCarbs());
            status.put("fat", budget.getBudgetStatus().getFat());
            status.put("sodium", budget.getBudgetStatus().getSodium());
            m.put("budget_status", status);
        }

        return m;
    }

    private int severityRank(String severity) {
        return switch (severity) {
            case "critical" -> 4;
            case "high" -> 3;
            case "moderate" -> 2;
            default -> 1;
        };
    }

    private ApiMeta buildMeta(String action) {
        return ApiMeta.builder()
                .requestId(UUID.randomUUID().toString())
                .timestamp(Instant.now().toString())
                .source("meal-analysis-" + action)
                .build();
    }
}
