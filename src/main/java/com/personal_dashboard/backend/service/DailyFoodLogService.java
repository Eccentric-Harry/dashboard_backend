package com.personal_dashboard.backend.service;

import com.personal_dashboard.backend.dto.DailyFoodLogDTO;
import com.personal_dashboard.backend.dto.FoodEntryDTO;
import com.personal_dashboard.backend.dto.MealEntryDTO;
import com.personal_dashboard.backend.model.DailyFoodLog;
import com.personal_dashboard.backend.model.DailyTotals;
import com.personal_dashboard.backend.model.MealEntry;
import com.personal_dashboard.backend.repository.DailyFoodLogRepository;
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

        DailyFoodLog dailyLog = dailyFoodLogRepository.findById(mealId).orElse(null);

        if (dailyLog == null) {
            // Create a new daily document
            dailyLog = DailyFoodLog.builder()
                    .id(mealId)
                    .mealId(mealId)
                    .date(date)
                    .dailyTotals(new DailyTotals())
                    .meals(new LinkedHashMap<>())
                    .build();
        }

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

        return dailyFoodLogRepository.save(dailyLog);
    }

    /**
     * Remove a specific meal entry from a daily log by its entry ID.
     * Returns the updated daily log, or null if nothing was found.
     */
    public DailyFoodLog removeMeal(String mealId, String entryId) {
        DailyFoodLog dailyLog = dailyFoodLogRepository.findById(mealId).orElse(null);
        if (dailyLog == null || dailyLog.getMeals() == null) {
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
        DailyFoodLog dailyLog = dailyFoodLogRepository.findById(mealId).orElse(null);
        if (dailyLog == null || dailyLog.getMeals() == null) {
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

    // ─── Query Operations ──────────────────────────────────────────────

    /**
     * Get the daily food log for a specific date.
     * Returns an empty skeleton if no log exists.
     */
    public DailyFoodLog getDailyLog(String dateStr) {
        return dailyFoodLogRepository.findById(dateStr)
                .orElse(DailyFoodLog.builder()
                        .id(dateStr)
                        .mealId(dateStr)
                        .date(LocalDate.parse(dateStr, DATE_FORMATTER))
                        .dailyTotals(new DailyTotals())
                        .meals(new LinkedHashMap<>())
                        .build());
    }

    /**
     * Get daily food logs for a date range (inclusive).
     */
    public List<DailyFoodLog> getDailyLogsForRange(LocalDate startDate, LocalDate endDate) {
        String startMealId = startDate.format(DATE_FORMATTER);
        String endMealId = endDate.format(DATE_FORMATTER);
        return dailyFoodLogRepository.findByMealIdRange(startMealId, endMealId);
    }

    /**
     * Check if a meal with the given importKey already exists in any daily log for the given date.
     */
    public boolean existsByImportKey(String dateStr, String importKey) {
        DailyFoodLog dailyLog = dailyFoodLogRepository.findById(dateStr).orElse(null);
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
     */
    public void recalculateTotals(DailyFoodLog log) {
        int totalCalories = 0;
        int totalProtein = 0;

        if (log.getMeals() != null) {
            for (List<MealEntry> entries : log.getMeals().values()) {
                if (entries != null) {
                    for (MealEntry entry : entries) {
                        totalCalories += entry.getCalories() != null ? entry.getCalories() : 0;
                        totalProtein += entry.getProteinGrams() != null ? entry.getProteinGrams() : 0;
                    }
                }
            }
        }

        if (log.getDailyTotals() == null) {
            log.setDailyTotals(new DailyTotals());
        }
        log.getDailyTotals().setTotalCalories(totalCalories);
        log.getDailyTotals().setTotalProteinGrams(totalProtein);
    }

    /**
     * Convert a DailyFoodLog to its DTO representation.
     */
    public DailyFoodLogDTO toDto(DailyFoodLog log) {
        Map<String, List<MealEntryDTO>> mealsDto = new LinkedHashMap<>();
        if (log.getMeals() != null) {
            for (Map.Entry<String, List<MealEntry>> entry : log.getMeals().entrySet()) {
                List<MealEntryDTO> dtoList = entry.getValue() != null
                        ? entry.getValue().stream().map(this::toMealEntryDto).collect(Collectors.toList())
                        : new ArrayList<>();
                mealsDto.put(entry.getKey(), dtoList);
            }
        }

        DailyFoodLogDTO.DailyTotalsDTO totalsDto = DailyFoodLogDTO.DailyTotalsDTO.builder()
                .totalCalories(log.getDailyTotals() != null ? log.getDailyTotals().getTotalCalories() : 0)
                .totalProteinGrams(log.getDailyTotals() != null ? log.getDailyTotals().getTotalProteinGrams() : 0)
                .build();

        return DailyFoodLogDTO.builder()
                .mealId(log.getMealId())
                .date(log.getDate() != null ? log.getDate().format(DATE_FORMATTER) : log.getId())
                .dailyTotals(totalsDto)
                .meals(mealsDto)
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
                .notes(entry.getNotes())
                .recipeCategory(entry.getRecipeCategory())
                .serving(entry.getServing())
                .servingNotes(entry.getServingNotes())
                .sourceNotes(entry.getSourceNotes())
                .timestamp(entry.getTimestamp() != null ? entry.getTimestamp().toString() : null)
                .build();
    }

    /**
     * Flatten a DailyFoodLog into a list of FoodEntryDTOs (backward compatibility).
     * Each entry gets the mealType injected from its map key.
     */
    public List<FoodEntryDTO> flattenToFoodEntryDTOs(DailyFoodLog log) {
        List<FoodEntryDTO> result = new ArrayList<>();
        if (log.getMeals() == null) return result;

        String dateStr = log.getDate() != null ? log.getDate().format(DATE_FORMATTER) : log.getId();

        for (Map.Entry<String, List<MealEntry>> mealGroup : log.getMeals().entrySet()) {
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
                        .notes(entry.getNotes())
                        .recipeCategory(entry.getRecipeCategory())
                        .serving(entry.getServing())
                        .servingNotes(entry.getServingNotes())
                        .sourceNotes(entry.getSourceNotes())
                        .build());
            }
        }

        return result;
    }
}
