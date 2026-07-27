package com.personal_dashboard.backend.service;

import com.personal_dashboard.backend.dto.DailyFoodLogDTO;
import com.personal_dashboard.backend.dto.FoodEntryDTO;
import com.personal_dashboard.backend.dto.HydrationRecordDTO;
import com.personal_dashboard.backend.dto.MealEntryDTO;
import com.personal_dashboard.backend.model.*;
import com.personal_dashboard.backend.repository.DailyFoodLogRepository;
import com.personal_dashboard.backend.repository.UserAccountRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class DailyFoodLogService {

    private final DailyFoodLogRepository dailyFoodLogRepository;
    private final UserAccountRepository userAccountRepository;
    private final org.springframework.data.mongodb.core.MongoTemplate mongoTemplate;

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE;

    // ─── Core Operations ───────────────────────────────────────────────

    /**
     * Add a meal to the daily food log for the given date.
     * Creates the daily document if it doesn't exist; appends to the correct mealType array if it does.
     */
    public DailyFoodLog addMeal(String dateStr, String mealType, MealEntry entry) {
        LocalDate date = LocalDate.parse(dateStr, DATE_FORMATTER);
        String mealId = dateStr;

        // Generate a UUID for this meal entry
        if (entry.getId() == null || entry.getId().isBlank()) {
            entry.setId(UUID.randomUUID().toString());
        }
        if (entry.getTimestamp() == null) {
            entry.setTimestamp(Instant.now());
        }

        String userId = com.personal_dashboard.backend.security.UserContext.getRequiredUserId();
        DailyFoodLog dailyLog = dailyFoodLogRepository.findByUserIdAndDateString(userId, dateStr).orElse(null);

        if (dailyLog == null) {
            // Create a new daily document
            dailyLog = DailyFoodLog.builder()
                    .userId(userId)
                    .dateString(dateStr)
                    .mealId(mealId)
                    .date(date)
                    .dailyTotals(new DailyTotals())
                    .meals(new LinkedHashMap<>())
                    .build();
        }

        ensureGoalsInitialized(dailyLog, null);

        // Ensure meals map is initialized
        if (dailyLog.getMeals() == null) {
            dailyLog.setMeals(new LinkedHashMap<>());
        }

        // Append the entry to the correct mealType list
        dailyLog.getMeals()
                .computeIfAbsent(mealType, k -> new ArrayList<>())
                .add(entry);

        // Recalculate totals
        recalculateTotals(dailyLog);

        DailyFoodLog saved = dailyFoodLogRepository.save(dailyLog);
        log.info("Added {} meal entry to log {} ({} cal, {}g protein)", mealType, dateStr, entry.getCalories(), entry.getProteinGrams());
        return saved;
    }

    /**
     * Remove a specific meal entry from a daily log by its entry ID.
     * Returns the updated daily log, or null if nothing was found.
     */
    public DailyFoodLog removeMeal(String mealId, String entryId) {
        log.info("Removing meal entry {} from log {}", entryId, mealId);
        String userId = com.personal_dashboard.backend.security.UserContext.getRequiredUserId();
        DailyFoodLog dailyLog = dailyFoodLogRepository.findByUserIdAndDateString(userId, mealId).orElse(null);
        if (dailyLog == null || dailyLog.getMeals() == null) {
            log.warn("No daily log found for {} — cannot remove meal entry {}", mealId, entryId);
            return null;
        }

        boolean removed = false;
        for (Map.Entry<String, List<MealEntry>> mealGroup : dailyLog.getMeals().entrySet()) {
            List<MealEntry> entries = mealGroup.getValue();
            if (entries != null) {
                removed = entries.removeIf(e -> entryId.equals(e.getId()));
                if (removed) {
                    break;
                }
            }
        }

        if (!removed) {
            log.warn("Meal entry {} not found in log {} — nothing removed", entryId, mealId);
            return null;
        }

        // Remove empty meal type lists
        dailyLog.getMeals().entrySet().removeIf(e -> e.getValue() == null || e.getValue().isEmpty());

        recalculateTotals(dailyLog);
        return dailyFoodLogRepository.save(dailyLog);
    }

    /**
     * Update a specific meal entry within a daily log.
     */
    public DailyFoodLog updateMeal(String mealId, String entryId, String newMealType, MealEntry updatedEntry) {
        log.info("Updating meal entry {} in log {}", entryId, mealId);
        String userId = com.personal_dashboard.backend.security.UserContext.getRequiredUserId();
        DailyFoodLog dailyLog = dailyFoodLogRepository.findByUserIdAndDateString(userId, mealId).orElse(null);
        if (dailyLog == null || dailyLog.getMeals() == null) {
            log.warn("No daily log found for {} — cannot update meal entry {}", mealId, entryId);
            return null;
        }

        // Find and remove the old entry
        MealEntry oldEntry = null;
        String oldMealType = null;
        for (Map.Entry<String, List<MealEntry>> mealGroup : dailyLog.getMeals().entrySet()) {
            List<MealEntry> entries = mealGroup.getValue();
            if (entries != null) {
                for (MealEntry e : entries) {
                    if (entryId.equals(e.getId())) {
                        oldEntry = e;
                        oldMealType = mealGroup.getKey();
                        break;
                    }
                }
                if (oldEntry != null) break;
            }
        }

        if (oldEntry == null) {
            log.warn("Meal entry {} not found in log {} — nothing updated", entryId, mealId);
            return null;
        }

        // Remove from old meal type
        dailyLog.getMeals().get(oldMealType).removeIf(e -> entryId.equals(e.getId()));

        // Preserve the original ID and timestamp
        updatedEntry.setId(entryId);
        if (updatedEntry.getTimestamp() == null) {
            updatedEntry.setTimestamp(oldEntry.getTimestamp());
        }

        // Add to new meal type (or same one)
        String targetMealType = newMealType != null ? newMealType : oldMealType;
        dailyLog.getMeals()
                .computeIfAbsent(targetMealType, k -> new ArrayList<>())
                .add(updatedEntry);

        // Remove empty meal type lists
        dailyLog.getMeals().entrySet().removeIf(e -> e.getValue() == null || e.getValue().isEmpty());

        recalculateTotals(dailyLog);
        return dailyFoodLogRepository.save(dailyLog);
    }

    /**
     * Update hydration data for the given date.
     */
    public DailyFoodLog updateHydration(String dateStr, Double waterIntakeMl, Double targetMl, String notes) {
        DailyFoodLog dailyLog = getDailyLogInternal(dateStr);

        if (dailyLog.getHydration() == null) {
            dailyLog.setHydration(new HydrationData());
        }

        if (waterIntakeMl != null) dailyLog.getHydration().setWaterIntakeMl(waterIntakeMl);
        if (targetMl != null) dailyLog.getHydration().setTargetMl(targetMl);
        if (notes != null) dailyLog.getHydration().setNotes(notes);

        log.info("Updated hydration for {}: {}ml / {}ml target", dateStr, waterIntakeMl, targetMl);
        return dailyFoodLogRepository.save(dailyLog);
    }

    /**
     * Increment water intake for the given date.
     */
    public DailyFoodLog addWaterIntake(String dateStr, Double amount) {
        DailyFoodLog dailyLog = getDailyLogInternal(dateStr);

        if (dailyLog.getHydration() == null) {
            dailyLog.setHydration(new HydrationData());
        }

        Double current = dailyLog.getHydration().getWaterIntakeMl();
        dailyLog.getHydration().setWaterIntakeMl(Math.max(0, current + amount));

        log.info("Added {}ml water intake for {} (new total: {}ml)", amount, dateStr, dailyLog.getHydration().getWaterIntakeMl());
        return dailyFoodLogRepository.save(dailyLog);
    }

    private void ensureGoalsInitialized(DailyFoodLog dailyLog, UserAccount user) {
        if (dailyLog.getCalorieGoal() == null || dailyLog.getProteinGoal() == null) {
            if (user == null && userAccountRepository != null) {
                user = userAccountRepository.findById(dailyLog.getUserId()).orElse(null);
            }
            if (user != null) {
                if (dailyLog.getCalorieGoal() == null) {
                    dailyLog.setCalorieGoal(user.getTargetCalories() != null ? user.getTargetCalories() : 2000);
                }
                if (dailyLog.getProteinGoal() == null) {
                    dailyLog.setProteinGoal(user.getTargetProtein() != null ? user.getTargetProtein() : 100);
                }
            } else {
                if (dailyLog.getCalorieGoal() == null) dailyLog.setCalorieGoal(2000);
                if (dailyLog.getProteinGoal() == null) dailyLog.setProteinGoal(100);
            }
        }
    }

    /**
     * Helper to get or create a daily log.
     */
    private DailyFoodLog getDailyLogInternal(String dateStr) {
        String userId = com.personal_dashboard.backend.security.UserContext.getRequiredUserId();
        DailyFoodLog dailyLog = dailyFoodLogRepository.findByUserIdAndDateString(userId, dateStr)
                .orElseGet(() -> DailyFoodLog.builder()
                        .userId(userId)
                        .dateString(dateStr)
                        .mealId(dateStr)
                        .date(LocalDate.parse(dateStr, DATE_FORMATTER))
                        .dailyTotals(new DailyTotals())
                        .meals(new LinkedHashMap<>())
                        .hydration(new HydrationData())
                        .build());
        ensureGoalsInitialized(dailyLog, null);
        return dailyLog;
    }


    // ─── Query Operations ──────────────────────────────────────────────

    /**
     * Get the daily food log for a specific date.
     * Returns an empty skeleton if no log exists.
     */
    public DailyFoodLog getDailyLog(String dateStr) {
        String userId = com.personal_dashboard.backend.security.UserContext.getRequiredUserId();
        DailyFoodLog dailyLog = dailyFoodLogRepository.findByUserIdAndDateString(userId, dateStr)
                .orElseGet(() -> DailyFoodLog.builder()
                        .userId(userId)
                        .dateString(dateStr)
                        .mealId(dateStr)
                        .date(LocalDate.parse(dateStr, DATE_FORMATTER))
                        .dailyTotals(new DailyTotals())
                        .meals(new LinkedHashMap<>())
                        .build());
        ensureGoalsInitialized(dailyLog, null);
        return dailyLog;
    }

    /**
     * Get daily food logs for a date range (inclusive).
     */
    public List<DailyFoodLog> getDailyLogsForRange(LocalDate startDate, LocalDate endDate) {
        String userId = com.personal_dashboard.backend.security.UserContext.getRequiredUserId();
        String startMealId = startDate.format(DATE_FORMATTER);
        String endMealId = endDate.format(DATE_FORMATTER);
        return dailyFoodLogRepository.findByUserIdAndDateStringRange(userId, startMealId, endMealId);
    }

    /**
     * Same range as {@link #getDailyLogsForRange} but server-side projected down to the fields
     * list/trend/analytics views actually render. The AI clinical payload on a single MealEntry
     * (mealItems, healthAnalysis, acneImpactAssessment, recompositionAssessment, dailyContext, …)
     * runs to several KB, so a year of meals is megabytes pulled out of Atlas on every page load.
     * This strips it inside MongoDB, before it ever crosses the wire.
     *
     * <p>Because {@code meals} is a Map keyed by meal type, the heavy fields can't be dropped with
     * a plain field projection — the pipeline rebuilds the map with $objectToArray/$arrayToObject.
     *
     * <p><b>The returned documents are partial — never save() them.</b> recalculateTotals() reads
     * mealItems, so persisting one would zero out the day's macro totals. Reads only.
     */
    public List<DailyFoodLog> getLightDailyLogsForRange(LocalDate startDate, LocalDate endDate) {
        String userId = com.personal_dashboard.backend.security.UserContext.getRequiredUserId();
        String startDateString = startDate.format(DATE_FORMATTER);
        String endDateString = endDate.format(DATE_FORMATTER);

        org.bson.Document match = new org.bson.Document("$match", new org.bson.Document()
                .append("userId", userId)
                .append("dateString", new org.bson.Document("$gte", startDateString).append("$lte", endDateString)));

        // Per-meal whitelist. total_summary/recomposition_assessment are kept but themselves
        // narrowed to the handful of keys the cards read (macros + letter grade).
        //
        // imageUrl is deliberately NOT here. Meals logged while AI dish-image generation was
        // enabled stored the thumbnail as an inline base64 data: URI, and those few records
        // dwarf everything else — measured at ~9.8 MB across 9 of 533 meals, 88% of the whole
        // collection's JSON. List views fall back to the bundled keyword-matched asset when
        // imageUrl is absent (see getMealImage), so dropping it here costs nothing; view=full
        // still carries it for the single-meal detail sheet.
        org.bson.Document lightMeal = new org.bson.Document()
                .append("id", "$$m.id")
                .append("description", "$$m.description")
                .append("calories", "$$m.calories")
                .append("proteinGrams", "$$m.proteinGrams")
                .append("mealQuality", "$$m.mealQuality")
                .append("recipeCategory", "$$m.recipeCategory")
                .append("serving", "$$m.serving")
                .append("timestamp", "$$m.timestamp")
                .append("totalSummary", new org.bson.Document()
                        .append("calories_kcal", "$$m.totalSummary.calories_kcal")
                        .append("protein_g", "$$m.totalSummary.protein_g")
                        .append("carbs_g", "$$m.totalSummary.carbs_g")
                        .append("carbohydrates_g", "$$m.totalSummary.carbohydrates_g")
                        .append("fat_g", "$$m.totalSummary.fat_g"))
                .append("recompositionAssessment", new org.bson.Document()
                        .append("letter_grade", "$$m.recompositionAssessment.letter_grade")
                        .append("meal_quality", "$$m.recompositionAssessment.meal_quality"));

        org.bson.Document lightMeals = new org.bson.Document("$arrayToObject",
                new org.bson.Document("$map", new org.bson.Document()
                        .append("input", new org.bson.Document("$objectToArray",
                                new org.bson.Document("$ifNull", List.of("$meals", new org.bson.Document()))))
                        .append("as", "g")
                        .append("in", new org.bson.Document()
                                .append("k", "$$g.k")
                                .append("v", new org.bson.Document("$map", new org.bson.Document()
                                        .append("input", new org.bson.Document("$ifNull", List.of("$$g.v", List.of())))
                                        .append("as", "m")
                                        .append("in", lightMeal))))));

        org.bson.Document project = new org.bson.Document("$project", new org.bson.Document()
                .append("userId", 1)
                .append("dateString", 1)
                .append("mealId", 1)
                .append("date", 1)
                .append("dailyTotals", 1)
                .append("calorieGoal", 1)
                .append("proteinGoal", 1)
                .append("hydration", 1)
                .append("meals", lightMeals));

        List<DailyFoodLog> results = new ArrayList<>();
        mongoTemplate.getCollection("daily_food_logs")
                .aggregate(List.of(match, project))
                .forEach(doc -> results.add(mongoTemplate.getConverter().read(DailyFoodLog.class, doc)));
        return results;
    }

    /**
     * Check if a meal with the given importKey already exists in any daily log for the given date.
     */
    public boolean existsByImportKey(String dateStr, String importKey) {
        String userId = com.personal_dashboard.backend.security.UserContext.getRequiredUserId();
        DailyFoodLog dailyLog = dailyFoodLogRepository.findByUserIdAndDateString(userId, dateStr).orElse(null);
        if (dailyLog == null || dailyLog.getMeals() == null) {
            return false;
        }

        return dailyLog.getMeals().values().stream()
                .filter(Objects::nonNull)
                .flatMap(Collection::stream)
                .anyMatch(entry -> importKey.equals(entry.getImportKey()));
    }

    // ─── Conversion Helpers ────────────────────────────────────────────

    /**
     * Recalculate dailyTotals by summing all meals.
     * Calories and protein are read from first-class fields.
     * Carbs, fat, fiber, sugar, sodium are extracted from the Gemini-populated mealItems payload.
     */
    public void recalculateTotals(DailyFoodLog dailyLog) {
        int totalCalories = 0;
        int totalProtein = 0;
        int totalCarbs = 0;
        int totalFat = 0;
        double totalFiber = 0.0;
        double totalSugar = 0.0;
        double totalSodium = 0.0;

        if (dailyLog.getMeals() != null) {
            for (List<MealEntry> entries : dailyLog.getMeals().values()) {
                if (entries != null) {
                    for (MealEntry entry : entries) {
                        totalCalories += entry.getCalories() != null ? entry.getCalories() : 0;
                        totalProtein += entry.getProteinGrams() != null ? entry.getProteinGrams() : 0;

                        // Aggregate macro/micro from mealItems (Gemini-populated)
                        if (entry.getMealItems() != null) {
                            for (Map<String, Object> item : entry.getMealItems()) {
                                totalCarbs += toInt(item.get("carbs"));
                                totalFat += toInt(item.get("fat"));
                                totalFiber += toDouble(item.get("fiber"));
                                totalSugar += toDouble(item.get("sugar"));
                                totalSodium += toDouble(item.get("sodium"));
                            }
                        }
                    }
                }
            }
        }

        if (dailyLog.getDailyTotals() == null) {
            dailyLog.setDailyTotals(new DailyTotals());
        }
        dailyLog.getDailyTotals().setTotalCalories(totalCalories);
        dailyLog.getDailyTotals().setTotalProteinGrams(totalProtein);
        dailyLog.getDailyTotals().setTotalCarbsGrams(totalCarbs);
        dailyLog.getDailyTotals().setTotalFatGrams(totalFat);
        dailyLog.getDailyTotals().setTotalFiberGrams(totalFiber);
        dailyLog.getDailyTotals().setTotalSugarGrams(totalSugar);
        dailyLog.getDailyTotals().setTotalSodiumMg(totalSodium);
        log.debug("Recalculated totals for log {}: {} cal, {}g protein, {}g carbs, {}g fat",
                dailyLog.getDateString(), totalCalories, totalProtein, totalCarbs, totalFat);
    }

    /** Safe int extraction from a Map value (Number or null). */
    private int toInt(Object value) {
        if (value instanceof Number) return ((Number) value).intValue();
        return 0;
    }

    /** Safe double extraction from a Map value (Number or null). */
    private double toDouble(Object value) {
        if (value instanceof Number) return ((Number) value).doubleValue();
        return 0.0;
    }


    /**
     * Convert a DailyFoodLog to its DTO representation.
     */
    public DailyFoodLogDTO toDto(DailyFoodLog dailyLog) {
        ensureGoalsInitialized(dailyLog, null);
        Map<String, List<MealEntryDTO>> mealsDto = new LinkedHashMap<>();
        if (dailyLog.getMeals() != null) {
            for (Map.Entry<String, List<MealEntry>> entry : dailyLog.getMeals().entrySet()) {
                List<MealEntryDTO> dtoList = entry.getValue() != null
                        ? entry.getValue().stream().map(this::toMealEntryDto).collect(Collectors.toList())
                        : new ArrayList<>();
                mealsDto.put(entry.getKey(), dtoList);
            }
        }

        DailyFoodLogDTO.DailyTotalsDTO totalsDto = DailyFoodLogDTO.DailyTotalsDTO.builder()
                .totalCalories(dailyLog.getDailyTotals() != null ? dailyLog.getDailyTotals().getTotalCalories() : 0)
                .totalProteinGrams(dailyLog.getDailyTotals() != null ? dailyLog.getDailyTotals().getTotalProteinGrams() : 0)
                .build();

        String dateStr = dailyLog.getDateString();

        return DailyFoodLogDTO.builder()
                .mealId(dailyLog.getDateString())
                .date(dateStr)
                .dailyTotals(totalsDto)
                .meals(mealsDto)
                .hydration(toHydrationDto(dateStr, dailyLog.getHydration()))
                .calorieGoal(dailyLog.getCalorieGoal())
                .proteinGoal(dailyLog.getProteinGoal())
                .build();
    }

    /**
     * Convert HydrationData to HydrationRecordDTO.
     */
    public HydrationRecordDTO toHydrationDto(String dateStr, HydrationData data) {
        if (data == null) {
            return HydrationRecordDTO.builder()
                    .date(dateStr)
                    .waterIntakeMl(0.0)
                    .targetMl(4000.0)
                    .progress(0.0)
                    .build();
        }

        double progress = data.getTargetMl() > 0
                ? (data.getWaterIntakeMl() / data.getTargetMl()) * 100.0
                : 0.0;
        progress = Math.min(progress, 100.0);

        return HydrationRecordDTO.builder()
                .date(dateStr)
                .waterIntakeMl(data.getWaterIntakeMl())
                .targetMl(data.getTargetMl())
                .progress(progress)
                .notes(data.getNotes())
                .build();
    }

    /**
     * Convert a MealEntry to its DTO.
     */
    public MealEntryDTO toMealEntryDto(MealEntry entry) {
        return MealEntryDTO.builder()
                .id(entry.getId())
                .description(entry.getDescription())
                .calories(entry.getCalories())
                .proteinGrams(entry.getProteinGrams())
                .mealQuality(entry.getMealQuality())
                .imageUrl(entry.getImageUrl())
                .notes(entry.getNotes())
                .recipeCategory(entry.getRecipeCategory())
                .serving(entry.getServing())
                .servingNotes(entry.getServingNotes())
                .sourceNotes(entry.getSourceNotes())
                .timestamp(entry.getTimestamp() != null ? entry.getTimestamp().toString() : null)
                .analysisMetadata(entry.getAnalysisMetadata())
                .mealItems(entry.getMealItems())
                .totalSummary(entry.getTotalSummary())
                .gapsAndWarnings(entry.getGapsAndWarnings())
                .technicalDiagnostic(entry.getTechnicalDiagnostic())
                .acneImpactAssessment(entry.getAcneImpactAssessment())
                .healthAnalysis(entry.getHealthAnalysis())
                .recompositionAssessment(entry.getRecompositionAssessment())
                .satietyAndEnergyProfile(entry.getSatietyAndEnergyProfile())
                .nutritionalBalanceDiagnostic(entry.getNutritionalBalanceDiagnostic())
                .dailyContext(entry.getDailyContext())
                .build();
    }

    /**
     * Flatten a DailyFoodLog into a list of FoodEntryDTOs (backward compatibility).
     * Each entry gets the mealType injected from its map key.
     */
    public List<FoodEntryDTO> flattenToFoodEntryDTOs(DailyFoodLog dailyLog) {
        List<FoodEntryDTO> result = new ArrayList<>();
        if (dailyLog.getMeals() == null) return result;

        String dateStr = dailyLog.getDateString();

        for (Map.Entry<String, List<MealEntry>> mealGroup : dailyLog.getMeals().entrySet()) {
            String mealType = mealGroup.getKey();
            List<MealEntry> entries = mealGroup.getValue();
            if (entries == null) continue;

            for (MealEntry entry : entries) {
                result.add(FoodEntryDTO.builder()
                        .id(entry.getId())
                        .description(entry.getDescription())
                        .calories(entry.getCalories())
                        .proteinGrams(entry.getProteinGrams())
                        .mealType(mealType)
                        .date(dateStr)
                        .mealQuality(entry.getMealQuality())
                        .imageUrl(entry.getImageUrl())
                        .notes(entry.getNotes())
                        .recipeCategory(entry.getRecipeCategory())
                        .serving(entry.getServing())
                        .servingNotes(entry.getServingNotes())
                        .sourceNotes(entry.getSourceNotes())
                        .analysisMetadata(entry.getAnalysisMetadata())
                        .mealItems(entry.getMealItems())
                        .totalSummary(entry.getTotalSummary())
                        .gapsAndWarnings(entry.getGapsAndWarnings())
                        .technicalDiagnostic(entry.getTechnicalDiagnostic())
                        .acneImpactAssessment(entry.getAcneImpactAssessment())
                        .healthAnalysis(entry.getHealthAnalysis())
                        .recompositionAssessment(entry.getRecompositionAssessment())
                        .satietyAndEnergyProfile(entry.getSatietyAndEnergyProfile())
                        .nutritionalBalanceDiagnostic(entry.getNutritionalBalanceDiagnostic())
                        .dailyContext(entry.getDailyContext())
                        .build());
            }
        }

        return result;
    }
}
