package com.personal_dashboard.backend.service;

import com.personal_dashboard.backend.dto.GeminiAnalysisResult;
import com.personal_dashboard.backend.dto.MealAnalysisResponse;
import com.personal_dashboard.backend.model.MealAnalysisJob;
import com.personal_dashboard.backend.model.MealEntry;
import com.personal_dashboard.backend.model.UserAccount;
import com.personal_dashboard.backend.repository.MealAnalysisJobRepository;
import com.personal_dashboard.backend.repository.UserAccountRepository;
import com.personal_dashboard.backend.security.UserContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Owns the asynchronous meal-analysis lifecycle: it creates a job record, runs
 * the two-stage Gemini pipeline (plus best-effort image generation and
 * persistence) on a background thread, and records the terminal result for the
 * client to poll.
 *
 * <p>The heavy Gemini-result → MealEntry mapping lives here (moved out of the
 * controller) so the controller stays a thin HTTP layer.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MealAnalysisJobService {

    private final NutritionPipelineService nutritionPipelineService;
    private final DailyFoodLogService dailyFoodLogService;
    private final UserAccountRepository userAccountRepository;
    private final GeminiImageGenerationService imageGenerationService;
    private final MealAnalysisJobRepository jobRepository;

    /**
     * Create a PENDING job for the given user and return it. Image bytes are read
     * on the caller's (request) thread and passed to {@link #process}, because the
     * uploaded multipart data is no longer readable once the request completes.
     */
    public MealAnalysisJob createJob(String userId, String mealType, String date, String description) {
        Instant now = Instant.now();
        MealAnalysisJob job = MealAnalysisJob.builder()
                .userId(userId)
                .status(MealAnalysisJob.Status.PENDING)
                .mealType(mealType)
                .date(date)
                .description(description)
                .createdAt(now)
                .updatedAt(now)
                .expiresAt(now)
                .build();
        return jobRepository.save(job);
    }

    public Optional<MealAnalysisJob> getJob(String jobId, String userId) {
        return jobRepository.findByIdAndUserId(jobId, userId);
    }

    /**
     * Runs the full pipeline on the dedicated executor and records the outcome on
     * the job. Never throws to the caller — failures are captured on the job's
     * {@code errorSource} so the polling client can render a contextual message.
     */
    @Async("mealAnalysisExecutor")
    public void process(
            String jobId,
            String userId,
            List<byte[]> images,
            String description,
            String mealType,
            String date) {

        // Re-bind the user onto this background thread — UserContext is a
        // ThreadLocal and does not propagate across the @Async boundary, yet the
        // persistence layer reads it to scope writes to the owner.
        UserContext.setUserId(userId);
        try {
            updateStatus(jobId, MealAnalysisJob.Status.PROCESSING, null, null);

            UserAccount userProfile = userAccountRepository.findById(userId).orElse(null);

            // ── Run Two-Stage Gemini Pipeline ────────────────────────────────
            GeminiAnalysisResult analysis;
            try {
                analysis = nutritionPipelineService.analyzeWithTwoStageFromBytes(
                        images.isEmpty() ? null : images,
                        (description != null && !description.isBlank()) ? description : null,
                        userProfile);
            } catch (Exception e) {
                log.error("[MealAnalysisJob {}] Gemini pipeline failed for user={}: {}", jobId, userId, e.getMessage(), e);
                updateStatus(jobId, MealAnalysisJob.Status.FAILED, "gemini-error", null);
                return;
            }

            // ── Map Gemini Output → MealEntry ───────────────────────────────
            GeminiAnalysisResult.MacroTotals totals = analysis.getMacroTotals();
            GeminiAnalysisResult.MealScore score = analysis.getMealScore();

            String mealDescription = buildDescription(analysis, description);

            // Best-effort pastel image; never blocks or fails the meal log.
            String generatedImageUrl = imageGenerationService.generatePastelFoodImageDataUri(mealDescription);

            List<Map<String, Object>> mealItemsMaps = mapIngredients(analysis.getIngredientsBreakdown());
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
                    .imageUrl(generatedImageUrl)
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
                dailyFoodLogService.addMeal(date, mealType, entry);
                savedEntryId = entry.getId() != null ? entry.getId() : "unknown";
            } catch (Exception e) {
                log.error("[MealAnalysisJob {}] Persistence failed for user={}: {}", jobId, userId, e.getMessage(), e);
                updateStatus(jobId, MealAnalysisJob.Status.FAILED, "persistence-error", null);
                return;
            }

            MealAnalysisResponse response = MealAnalysisResponse.builder()
                    .mealEntryId(savedEntryId)
                    .mealType(mealType)
                    .date(date)
                    .description(mealDescription)
                    .calories(calories)
                    .proteinGrams(protein)
                    .imageUrl(generatedImageUrl)
                    .analysis(analysis)
                    .build();

            updateStatus(jobId, MealAnalysisJob.Status.COMPLETED, null, response);
            log.info("[MealAnalysisJob {}] Completed for user={} — entry={}", jobId, userId, savedEntryId);
        } catch (Exception e) {
            // Defensive catch-all so a job is never left stuck in PROCESSING.
            log.error("[MealAnalysisJob {}] Unexpected failure: {}", jobId, e.getMessage(), e);
            updateStatus(jobId, MealAnalysisJob.Status.FAILED, "gemini-error", null);
        } finally {
            UserContext.clear();
        }
    }

    private void updateStatus(String jobId, MealAnalysisJob.Status status, String errorSource, MealAnalysisResponse result) {
        jobRepository.findById(jobId).ifPresent(job -> {
            job.setStatus(status);
            job.setUpdatedAt(Instant.now());
            if (errorSource != null) job.setErrorSource(errorSource);
            if (result != null) job.setResult(result);
            jobRepository.save(job);
        });
    }

    // ─── Gemini result → MealEntry mapping (moved from MealAnalysisController) ──

    private String buildDescription(GeminiAnalysisResult analysis, String fallbackDescription) {
        if (analysis.getMealLabel() != null && !analysis.getMealLabel().isBlank()) {
            return analysis.getMealLabel();
        }
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
            m.put("confidence", "high");
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

        if (analysis.getClinicalFlags() != null && !analysis.getClinicalFlags().isEmpty()) {
            Map<String, List<GeminiAnalysisResult.ClinicalFlag>> byCondition =
                    analysis.getClinicalFlags().stream()
                            .collect(Collectors.groupingBy(f ->
                                    f.getConditionLink() != null ? f.getConditionLink() : "General"));

            List<Map<String, Object>> medicalAnalysis = byCondition.entrySet().stream().map(entry -> {
                Map<String, Object> conditionMap = new LinkedHashMap<>();
                conditionMap.put("condition", entry.getKey());

                String maxSeverity = entry.getValue().stream()
                        .map(GeminiAnalysisResult.ClinicalFlag::getSeverity)
                        .map(s -> s != null ? s.toLowerCase() : "low")
                        .reduce((a, b) -> severityRank(a) > severityRank(b) ? a : b)
                        .orElse("low");
                conditionMap.put("risk", maxSeverity);

                conditionMap.put("findings", entry.getValue().stream()
                        .map(f -> f.getTitle() + ": " + f.getMechanisticPathway())
                        .collect(Collectors.toList()));

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
            if (score.getMealContext() != null) {
                m.put("meal_context", score.getMealContext());
            }
        }

        if (analysis.getPositiveHighlights() != null) {
            m.put("strengths", analysis.getPositiveHighlights().stream()
                    .map(h -> h.getIngredientOrAspect() + ": " + h.getBenefit())
                    .collect(Collectors.toList()));
        }

        if (analysis.getClinicalFlags() != null) {
            m.put("concerns", analysis.getClinicalFlags().stream()
                    .map(GeminiAnalysisResult.ClinicalFlag::getTitle)
                    .collect(Collectors.toList()));
        }

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

    /** Best-effort target date: use the provided value or default to today. */
    public static String resolveTargetDate(String date) {
        return (date != null && !date.isBlank()) ? date : LocalDate.now().toString();
    }
}
