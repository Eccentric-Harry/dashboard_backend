package com.personal_dashboard.backend.controller;

import com.personal_dashboard.backend.dto.*;
import com.personal_dashboard.backend.dto.request.FoodEntryRequest;
import com.personal_dashboard.backend.dto.request.HydrationRequest;
import com.personal_dashboard.backend.model.*;
import com.personal_dashboard.backend.service.DailyFoodLogService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/health")
@RequiredArgsConstructor
public class HealthController {

    private final DailyFoodLogService dailyFoodLogService;

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final double DEFAULT_TARGET_ML = 4000.0;

    // ─── Food Endpoints ────────────────────────────────────────────────

    /**
     * Add a meal entry to the daily food log.
     * If a daily document for the date already exists, the meal is appended.
     * If not, a new daily document is created.
     */
    @PostMapping("/food")
    public ResponseEntity<ApiResponse<FoodEntryDTO>> createFoodEntry(
            @Valid @RequestBody FoodEntryRequest request) {

        MealEntry entry = MealEntry.builder()
                .description(request.getDescription())
                .calories(request.getCalories())
                .proteinGrams(request.getProteinGrams())
                .timestamp(Instant.now())
                .build();

        DailyFoodLog savedLog = dailyFoodLogService.addMeal(
                request.getDate(), request.getMealType(), entry);

        // Return the newly added entry as a FoodEntryDTO (backward compat)
        FoodEntryDTO responseDto = FoodEntryDTO.builder()
                .id(entry.getId())
                .description(entry.getDescription())
                .calories(entry.getCalories())
                .proteinGrams(entry.getProteinGrams())
                .mealType(request.getMealType())
                .date(request.getDate())
                .build();

        return ResponseEntity.status(HttpStatus.CREATED).body(buildResponse(responseDto));
    }

    /**
     * Update a meal entry within a daily log.
     * The {mealId} is the date string, {entryId} is the UUID of the meal entry.
     */
    @PutMapping("/food/{mealId}/meal/{entryId}")
    public ResponseEntity<ApiResponse<FoodEntryDTO>> updateFoodEntry(
            @PathVariable String mealId,
            @PathVariable String entryId,
            @Valid @RequestBody FoodEntryRequest request) {

        MealEntry updatedEntry = MealEntry.builder()
                .description(request.getDescription())
                .calories(request.getCalories())
                .proteinGrams(request.getProteinGrams())
                .build();

        DailyFoodLog result = dailyFoodLogService.updateMeal(
                mealId, entryId, request.getMealType(), updatedEntry);

        if (result == null) {
            throw new RuntimeException("Meal entry not found: mealId=" + mealId + ", entryId=" + entryId);
        }

        FoodEntryDTO responseDto = FoodEntryDTO.builder()
                .id(entryId)
                .description(request.getDescription())
                .calories(request.getCalories())
                .proteinGrams(request.getProteinGrams())
                .mealType(request.getMealType())
                .date(mealId)
                .build();

        return ResponseEntity.ok(buildResponse(responseDto));
    }

    /**
     * Delete a specific meal entry from a daily log.
     */
    @DeleteMapping("/food/{mealId}/meal/{entryId}")
    public ResponseEntity<ApiResponse<Void>> deleteFoodEntry(
            @PathVariable String mealId,
            @PathVariable String entryId) {

        DailyFoodLog result = dailyFoodLogService.removeMeal(mealId, entryId);
        if (result == null) {
            throw new RuntimeException("Meal entry not found: mealId=" + mealId + ", entryId=" + entryId);
        }

        return ResponseEntity.ok(buildResponse(null));
    }

    /**
     * Get food entries as a flat list (backward compatibility).
     * Supports filtering by date range, days, and meal type.
     */
    @GetMapping("/food")
    public ResponseEntity<ApiResponse<List<FoodEntryDTO>>> getFoodEntries(
            @RequestParam(value = "days", required = false) Integer days,
            @RequestParam(value = "startDate", required = false) String startDateStr,
            @RequestParam(value = "endDate", required = false) String endDateStr,
            @RequestParam(value = "mealType", required = false) String mealType) {

        LocalDate endDate = LocalDate.now();
        LocalDate startDate;

        if (startDateStr != null && !startDateStr.isEmpty()) {
            startDate = LocalDate.parse(startDateStr, DATE_FORMATTER);
            if (endDateStr != null && !endDateStr.isEmpty()) {
                endDate = LocalDate.parse(endDateStr, DATE_FORMATTER);
            }
        } else {
            int daysToSubtract = days != null ? days : 30;
            startDate = endDate.minusDays(daysToSubtract);
        }

        List<DailyFoodLog> dailyLogs = dailyFoodLogService.getDailyLogsForRange(startDate, endDate);

        // Flatten daily logs into individual FoodEntryDTOs
        List<FoodEntryDTO> dtos = dailyLogs.stream()
                .flatMap(log -> dailyFoodLogService.flattenToFoodEntryDTOs(log).stream())
                .sorted((a, b) -> b.getDate().compareTo(a.getDate()))
                .collect(Collectors.toList());

        // Filter by meal type if specified
        if (mealType != null && !mealType.isEmpty()) {
            String normalizedMealType = mealType.substring(0, 1).toUpperCase() + mealType.substring(1).toLowerCase();
            dtos = dtos.stream()
                    .filter(e -> e.getMealType().equalsIgnoreCase(normalizedMealType))
                    .toList();
        }

        return ResponseEntity.ok(buildResponse(dtos));
    }

    /**
     * Get a single daily food log with nested meals (new endpoint).
     */
    @GetMapping("/food/daily")
    public ResponseEntity<ApiResponse<DailyFoodLogDTO>> getDailyFoodLog(
            @RequestParam(value = "date", required = false) String dateStr) {

        String targetDate = dateStr != null && !dateStr.isEmpty()
                ? dateStr
                : LocalDate.now().format(DATE_FORMATTER);

        DailyFoodLog log = dailyFoodLogService.getDailyLog(targetDate);
        DailyFoodLogDTO dto = dailyFoodLogService.toDto(log);

        return ResponseEntity.ok(buildResponse(dto));
    }

    // ─── Hydration Endpoints (unchanged) ───────────────────────────────

    @PostMapping("/hydration")
    public ResponseEntity<ApiResponse<HydrationRecordDTO>> createOrUpdateHydration(
            @Valid @RequestBody HydrationRequest request) {

        double target = request.getTargetMl() != null ? request.getTargetMl() : DEFAULT_TARGET_ML;

        DailyFoodLog log = dailyFoodLogService.updateHydration(
                request.getDate(),
                request.getWaterIntakeMl(),
                target,
                request.getNotes()
        );

        HydrationRecordDTO responseDto = dailyFoodLogService.toHydrationDto(request.getDate(), log.getHydration());

        return ResponseEntity.status(HttpStatus.CREATED).body(buildResponse(responseDto));
    }

    @GetMapping("/hydration")
    public ResponseEntity<ApiResponse<HydrationRecordDTO>> getHydration(
            @RequestParam(value = "days", required = false) Integer days,
            @RequestParam(value = "date", required = false) String dateStr) {

        String targetDate = dateStr != null && !dateStr.isEmpty()
                ? dateStr
                : LocalDate.now().format(DATE_FORMATTER);

        DailyFoodLog log = dailyFoodLogService.getDailyLog(targetDate);
        HydrationRecordDTO responseDto = dailyFoodLogService.toHydrationDto(targetDate, log.getHydration());

        return ResponseEntity.ok(buildResponse(responseDto));
    }

    @PutMapping("/hydration/{id}")
    public ResponseEntity<ApiResponse<HydrationRecordDTO>> updateHydration(
            @PathVariable String id,
            @Valid @RequestBody HydrationRequest request) {
        
        // Note: id (date string) is used as id in the new schema
        return createOrUpdateHydration(request);
    }

    @DeleteMapping("/hydration/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteHydration(@PathVariable String id) {
        // Hydration is now part of the daily log, we don't 'delete' it usually, 
        // but we can reset it if needed. For now, let's just return success 
        // since the user wants to rely on daily_food_logs and hydration is embedded.
        
        dailyFoodLogService.updateHydration(id, 0.0, DEFAULT_TARGET_ML, null);

        return ResponseEntity.ok(buildResponse(null));
    }

    @PostMapping("/hydration/add")
    public ResponseEntity<ApiResponse<HydrationRecordDTO>> addWaterIntake(
            @RequestParam(value = "date", required = false) String dateStr,
            @RequestParam(value = "amount", required = true) Double amount) {

        String targetDate = dateStr != null && !dateStr.isEmpty()
                ? dateStr
                : LocalDate.now().format(DATE_FORMATTER);

        DailyFoodLog log = dailyFoodLogService.addWaterIntake(targetDate, amount);
        HydrationRecordDTO responseDto = dailyFoodLogService.toHydrationDto(targetDate, log.getHydration());

        return ResponseEntity.ok(buildResponse(responseDto));
    }

    // ─── Private Helpers ───────────────────────────────────────────────


    private <T> ApiResponse<T> buildResponse(T data) {
        ApiMeta meta = ApiMeta.builder()
                .requestId(UUID.randomUUID().toString())
                .timestamp(Instant.now().toString())
                .source("api")
                .build();

        return ApiResponse.<T>builder()
                .data(data)
                .meta(meta)
                .build();
    }
}
