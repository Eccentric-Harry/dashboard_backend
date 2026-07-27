package com.personal_dashboard.backend.controller;

import com.personal_dashboard.backend.dto.*;
import com.personal_dashboard.backend.dto.request.FoodEntryRequest;
import com.personal_dashboard.backend.dto.request.HydrationRequest;
import com.personal_dashboard.backend.model.*;
import com.personal_dashboard.backend.service.DailyFoodLogService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
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
                log.info("POST /health/food — {} on {}", request.getMealType(), request.getDate());

                MealEntry entry = MealEntry.builder()
                                .description(request.getDescription())
                                .calories(request.getCalories())
                                .proteinGrams(request.getProteinGrams())
                                .timestamp(request.getTimestamp() != null ? request.getTimestamp() : Instant.now())
                                .mealQuality(request.getMealQuality())
                                .notes(request.getNotes())
                                .recipeCategory(request.getRecipeCategory())
                                .serving(request.getServing())
                                .servingNotes(request.getServingNotes())
                                .sourceNotes(request.getSourceNotes())
                                .importKey(request.getImportKey())
                                .analysisMetadata(request.getAnalysisMetadata())
                                .mealItems(request.getMealItems())
                                .totalSummary(request.getTotalSummary())
                                .gapsAndWarnings(request.getGapsAndWarnings())
                                .technicalDiagnostic(request.getTechnicalDiagnostic())
                                .acneImpactAssessment(request.getAcneImpactAssessment())
                                .healthAnalysis(request.getHealthAnalysis())
                                .recompositionAssessment(request.getRecompositionAssessment())
                                .satietyAndEnergyProfile(request.getSatietyAndEnergyProfile())
                                .nutritionalBalanceDiagnostic(request.getNutritionalBalanceDiagnostic())
                                .dailyContext(request.getDailyContext())
                                .build();

                // Save the newly added entry to MongoDB
                dailyFoodLogService.addMeal(request.getDate(), request.getMealType(), entry);

                // Return the newly added entry as a FoodEntryDTO (backward compat)
                FoodEntryDTO responseDto = FoodEntryDTO.builder()
                                .id(entry.getId())
                                .description(entry.getDescription())
                                .calories(entry.getCalories())
                                .proteinGrams(entry.getProteinGrams())
                                .mealType(request.getMealType())
                                .date(request.getDate())
                                .mealQuality(entry.getMealQuality())
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
                log.info("PUT /health/food/{}/meal/{}", mealId, entryId);

                MealEntry updatedEntry = MealEntry.builder()
                                .description(request.getDescription())
                                .calories(request.getCalories())
                                .proteinGrams(request.getProteinGrams())
                                .timestamp(request.getTimestamp())
                                .mealQuality(request.getMealQuality())
                                .notes(request.getNotes())
                                .recipeCategory(request.getRecipeCategory())
                                .serving(request.getServing())
                                .servingNotes(request.getServingNotes())
                                .sourceNotes(request.getSourceNotes())
                                .importKey(request.getImportKey())
                                .analysisMetadata(request.getAnalysisMetadata())
                                .mealItems(request.getMealItems())
                                .totalSummary(request.getTotalSummary())
                                .gapsAndWarnings(request.getGapsAndWarnings())
                                .technicalDiagnostic(request.getTechnicalDiagnostic())
                                .acneImpactAssessment(request.getAcneImpactAssessment())
                                .healthAnalysis(request.getHealthAnalysis())
                                .recompositionAssessment(request.getRecompositionAssessment())
                                .satietyAndEnergyProfile(request.getSatietyAndEnergyProfile())
                                .nutritionalBalanceDiagnostic(request.getNutritionalBalanceDiagnostic())
                                .dailyContext(request.getDailyContext())
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
                                .mealQuality(request.getMealQuality())
                                .notes(request.getNotes())
                                .recipeCategory(request.getRecipeCategory())
                                .serving(request.getServing())
                                .servingNotes(request.getServingNotes())
                                .sourceNotes(request.getSourceNotes())
                                .analysisMetadata(request.getAnalysisMetadata())
                                .mealItems(request.getMealItems())
                                .totalSummary(request.getTotalSummary())
                                .gapsAndWarnings(request.getGapsAndWarnings())
                                .technicalDiagnostic(request.getTechnicalDiagnostic())
                                .acneImpactAssessment(request.getAcneImpactAssessment())
                                .healthAnalysis(request.getHealthAnalysis())
                                .recompositionAssessment(request.getRecompositionAssessment())
                                .satietyAndEnergyProfile(request.getSatietyAndEnergyProfile())
                                .nutritionalBalanceDiagnostic(request.getNutritionalBalanceDiagnostic())
                                .dailyContext(request.getDailyContext())
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
                log.info("DELETE /health/food/{}/meal/{}", mealId, entryId);

                DailyFoodLog result = dailyFoodLogService.removeMeal(mealId, entryId);
                if (result == null) {
                        throw new RuntimeException("Meal entry not found: mealId=" + mealId + ", entryId=" + entryId);
                }

                return ResponseEntity.ok(buildResponse(null));
        }

        /**
         * Get food entries as a flat list (backward compatibility).
         * Supports filtering by date range, days, and meal type.
         *
         * <p>Defaults to the <b>summary</b> view: identifiers, macros, meal quality and the
         * two nested keys the list/analytics cards read. The full AI clinical payload runs to
         * several KB per meal, so a multi-month range in full view is megabytes — pass
         * {@code view=full} only when rendering a single meal's detail sheet.
         */
        @GetMapping("/food")
        public ResponseEntity<ApiResponse<List<FoodEntryDTO>>> getFoodEntries(
                        @RequestParam(value = "days", required = false) Integer days,
                        @RequestParam(value = "startDate", required = false) String startDateStr,
                        @RequestParam(value = "endDate", required = false) String endDateStr,
                        @RequestParam(value = "mealType", required = false) String mealType,
                        @RequestParam(value = "view", required = false) String view) {

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

                boolean fullView = "full".equalsIgnoreCase(view);
                List<DailyFoodLog> dailyLogs = fullView
                                ? dailyFoodLogService.getDailyLogsForRange(startDate, endDate)
                                : dailyFoodLogService.getLightDailyLogsForRange(startDate, endDate);

                // Flatten daily logs into individual FoodEntryDTOs
                List<FoodEntryDTO> dtos = dailyLogs.stream()
                                .flatMap(dailyLog -> dailyFoodLogService.flattenToFoodEntryDTOs(dailyLog).stream())
                                .sorted((a, b) -> b.getDate().compareTo(a.getDate()))
                                .collect(Collectors.toList());

                // Filter by meal type if specified
                if (mealType != null && !mealType.isEmpty()) {
                        String normalizedMealType = mealType.substring(0, 1).toUpperCase()
                                        + mealType.substring(1).toLowerCase();
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

                DailyFoodLog dailyLog = dailyFoodLogService.getDailyLog(targetDate);
                DailyFoodLogDTO dto = dailyFoodLogService.toDto(dailyLog);

                return ResponseEntity.ok(buildResponse(dto));
        }

        // ─── Hydration Endpoints (unchanged) ───────────────────────────────

        @PostMapping("/hydration")
        public ResponseEntity<ApiResponse<HydrationRecordDTO>> createOrUpdateHydration(
                        @Valid @RequestBody HydrationRequest request) {

                double target = request.getTargetMl() != null ? request.getTargetMl() : DEFAULT_TARGET_ML;

                DailyFoodLog dailyLog = dailyFoodLogService.updateHydration(
                                request.getDate(),
                                request.getWaterIntakeMl(),
                                target,
                                request.getNotes());

                HydrationRecordDTO responseDto = dailyFoodLogService.toHydrationDto(request.getDate(),
                                dailyLog.getHydration());

                return ResponseEntity.status(HttpStatus.CREATED).body(buildResponse(responseDto));
        }

        @GetMapping("/hydration")
        public ResponseEntity<ApiResponse<HydrationRecordDTO>> getHydration(
                        @RequestParam(value = "days", required = false) Integer days,
                        @RequestParam(value = "date", required = false) String dateStr) {

                String targetDate = dateStr != null && !dateStr.isEmpty()
                                ? dateStr
                                : LocalDate.now().format(DATE_FORMATTER);

                DailyFoodLog dailyLog = dailyFoodLogService.getDailyLog(targetDate);
                HydrationRecordDTO responseDto = dailyFoodLogService.toHydrationDto(targetDate, dailyLog.getHydration());

                return ResponseEntity.ok(buildResponse(responseDto));
        }

        /**
         * Get hydration records for a date range (or the last N days), one entry per day.
         */
        @GetMapping("/hydration/range")
        public ResponseEntity<ApiResponse<List<HydrationRecordDTO>>> getHydrationRange(
                        @RequestParam(value = "days", required = false) Integer days,
                        @RequestParam(value = "startDate", required = false) String startDateStr,
                        @RequestParam(value = "endDate", required = false) String endDateStr) {

                LocalDate endDate = LocalDate.now();
                LocalDate startDate;

                if (startDateStr != null && !startDateStr.isEmpty()) {
                        startDate = LocalDate.parse(startDateStr, DATE_FORMATTER);
                        if (endDateStr != null && !endDateStr.isEmpty()) {
                                endDate = LocalDate.parse(endDateStr, DATE_FORMATTER);
                        }
                } else {
                        int daysToSubtract = days != null ? days : 14;
                        startDate = endDate.minusDays(daysToSubtract);
                }

                // Hydration lives outside `meals`, so the projected read is enough here.
                List<DailyFoodLog> dailyLogs = dailyFoodLogService.getLightDailyLogsForRange(startDate, endDate);

                List<HydrationRecordDTO> dtos = dailyLogs.stream()
                                .map(dailyLog -> dailyFoodLogService.toHydrationDto(dailyLog.getDateString(), dailyLog.getHydration()))
                                .sorted(Comparator.comparing(HydrationRecordDTO::getDate))
                                .collect(Collectors.toList());

                return ResponseEntity.ok(buildResponse(dtos));
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
                log.info("Resetting hydration for {}", id);
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

                DailyFoodLog dailyLog = dailyFoodLogService.addWaterIntake(targetDate, amount);
                HydrationRecordDTO responseDto = dailyFoodLogService.toHydrationDto(targetDate, dailyLog.getHydration());

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
