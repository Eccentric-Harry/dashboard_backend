package com.personal_dashboard.backend.controller;

import com.personal_dashboard.backend.dto.ApiMeta;
import com.personal_dashboard.backend.dto.ApiResponse;
import com.personal_dashboard.backend.dto.FoodEntryDTO;
import com.personal_dashboard.backend.dto.HydrationRecordDTO;
import com.personal_dashboard.backend.dto.request.FoodEntryRequest;
import com.personal_dashboard.backend.dto.request.HydrationRequest;
import com.personal_dashboard.backend.model.FoodEntry;
import com.personal_dashboard.backend.model.HydrationRecord;
import com.personal_dashboard.backend.repository.FoodEntryRepository;
import com.personal_dashboard.backend.repository.HydrationRecordRepository;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/health")
@RequiredArgsConstructor
public class HealthController {

    private final FoodEntryRepository foodEntryRepository;
    private final HydrationRecordRepository hydrationRecordRepository;

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final double DEFAULT_TARGET_ML = 4000.0;

    @PostMapping("/food")
    public ResponseEntity<ApiResponse<FoodEntryDTO>> createFoodEntry(
            @Valid @RequestBody FoodEntryRequest request) {

        LocalDate localDate = LocalDate.parse(request.getDate(), DATE_FORMATTER);

        // Create and save the food entry
        FoodEntry foodEntry = FoodEntry.builder()
                .description(request.getDescription())
                .calories(request.getCalories())
                .proteinGrams(request.getProteinGrams())
                .mealType(request.getMealType())
                .date(localDate)
                .build();

        FoodEntry savedEntry = foodEntryRepository.save(foodEntry);

        FoodEntryDTO responseDto = toDto(savedEntry);

        ApiMeta meta = ApiMeta.builder()
                .requestId(UUID.randomUUID().toString())
                .timestamp(Instant.now().toString())
                .source("api")
                .build();

        ApiResponse<FoodEntryDTO> response = ApiResponse.<FoodEntryDTO>builder()
                .data(responseDto)
                .meta(meta)
                .build();

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PutMapping("/food/{id}")
    public ResponseEntity<ApiResponse<FoodEntryDTO>> updateFoodEntry(
            @PathVariable String id,
            @Valid @RequestBody FoodEntryRequest request) {

        FoodEntry existingEntry = foodEntryRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Food entry not found with id: " + id));

        LocalDate localDate = LocalDate.parse(request.getDate(), DATE_FORMATTER);

        existingEntry.setDescription(request.getDescription());
        existingEntry.setCalories(request.getCalories());
        existingEntry.setProteinGrams(request.getProteinGrams());
        existingEntry.setMealType(request.getMealType());
        existingEntry.setDate(localDate);

        FoodEntry updatedEntry = foodEntryRepository.save(existingEntry);

        FoodEntryDTO responseDto = toDto(updatedEntry);

        ApiMeta meta = ApiMeta.builder()
                .requestId(UUID.randomUUID().toString())
                .timestamp(Instant.now().toString())
                .source("api")
                .build();

        ApiResponse<FoodEntryDTO> response = ApiResponse.<FoodEntryDTO>builder()
                .data(responseDto)
                .meta(meta)
                .build();

        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/food/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteFoodEntry(@PathVariable String id) {
        
        if (!foodEntryRepository.existsById(id)) {
            throw new RuntimeException("Food entry not found with id: " + id);
        }

        foodEntryRepository.deleteById(id);

        ApiMeta meta = ApiMeta.builder()
                .requestId(UUID.randomUUID().toString())
                .timestamp(Instant.now().toString())
                .source("api")
                .build();

        ApiResponse<Void> response = ApiResponse.<Void>builder()
                .data(null)
                .meta(meta)
                .build();

        return ResponseEntity.ok(response);
    }

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

        List<FoodEntry> entries = getFoodEntriesForDateRange(startDate, endDate);

        if (mealType != null && !mealType.isEmpty()) {
            String normalizedMealType = mealType.substring(0, 1).toUpperCase() + mealType.substring(1).toLowerCase();
            entries = entries.stream()
                    .filter(e -> e.getMealType().equalsIgnoreCase(normalizedMealType))
                    .toList();
        }

        List<FoodEntryDTO> dtos = entries.stream()
                .map(this::toDto)
                .sorted((a, b) -> b.getDate().compareTo(a.getDate())) // Sort by date descending
                .toList();

        ApiMeta meta = ApiMeta.builder()
                .requestId(UUID.randomUUID().toString())
                .timestamp(Instant.now().toString())
                .source("api")
                .build();

        ApiResponse<List<FoodEntryDTO>> response = ApiResponse.<List<FoodEntryDTO>>builder()
                .data(dtos)
                .meta(meta)
                .build();

        return ResponseEntity.ok(response);
    }

    private FoodEntryDTO toDto(FoodEntry entry) {
        return FoodEntryDTO.builder()
                .id(entry.getId())
                .description(entry.getDescription())
                .calories(entry.getCalories())
                .proteinGrams(entry.getProteinGrams())
                .mealType(entry.getMealType())
                .date(entry.getDate().format(DATE_FORMATTER))
                .mealQuality(entry.getMealQuality())
                .notes(entry.getNotes())
                .recipeCategory(entry.getRecipeCategory())
                .serving(entry.getServing())
                .servingNotes(entry.getServingNotes())
                .sourceNotes(entry.getSourceNotes())
                .build();
    }

    private List<FoodEntry> getFoodEntriesForDateRange(LocalDate startDate, LocalDate endDate) {
        List<FoodEntry> entries = new java.util.ArrayList<>();

        for (LocalDate date = startDate; !date.isAfter(endDate); date = date.plusDays(1)) {
            entries.addAll(foodEntryRepository.findByDate(date));
        }

        return entries;
    }

    @PostMapping("/hydration")
    public ResponseEntity<ApiResponse<HydrationRecordDTO>> createOrUpdateHydration(
            @Valid @RequestBody HydrationRequest request) {

        LocalDate localDate = LocalDate.parse(request.getDate(), DATE_FORMATTER);
        double target = request.getTargetMl() != null ? request.getTargetMl() : DEFAULT_TARGET_ML;

        HydrationRecord existingRecord = hydrationRecordRepository.findFirstByDate(localDate)
                .orElse(null);

        HydrationRecord record;
        if (existingRecord != null) {
            existingRecord.setWaterIntakeMl(request.getWaterIntakeMl());
            existingRecord.setTargetMl(target);
            existingRecord.setNotes(request.getNotes());
            record = hydrationRecordRepository.save(existingRecord);
        } else {
            record = HydrationRecord.builder()
                    .date(localDate)
                    .waterIntakeMl(request.getWaterIntakeMl())
                    .targetMl(target)
                    .notes(request.getNotes())
                    .build();
            record = hydrationRecordRepository.save(record);
        }

        HydrationRecordDTO responseDto = toHydrationDto(record);

        ApiMeta meta = ApiMeta.builder()
                .requestId(UUID.randomUUID().toString())
                .timestamp(Instant.now().toString())
                .source("api")
                .build();

        ApiResponse<HydrationRecordDTO> response = ApiResponse.<HydrationRecordDTO>builder()
                .data(responseDto)
                .meta(meta)
                .build();

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/hydration")
    public ResponseEntity<ApiResponse<HydrationRecordDTO>> getHydration(
            @RequestParam(value = "days", required = false) Integer days,
            @RequestParam(value = "date", required = false) String dateStr) {

        HydrationRecordDTO responseDto;
        LocalDate targetDate;

        if (dateStr != null && !dateStr.isEmpty()) {
            targetDate = LocalDate.parse(dateStr, DATE_FORMATTER);
            HydrationRecord record = hydrationRecordRepository.findFirstByDate(targetDate).orElse(null);
            if (record == null) {
                responseDto = HydrationRecordDTO.builder()
                        .date(dateStr)
                        .waterIntakeMl(0.0)
                        .targetMl(DEFAULT_TARGET_ML)
                        .progress(0.0)
                        .build();
            } else {
                responseDto = toHydrationDto(record);
            }
        } else {
            targetDate = LocalDate.now();
            HydrationRecord record = hydrationRecordRepository.findFirstByDate(targetDate).orElse(null);
            if (record == null) {
                responseDto = HydrationRecordDTO.builder()
                        .date(targetDate.format(DATE_FORMATTER))
                        .waterIntakeMl(0.0)
                        .targetMl(DEFAULT_TARGET_ML)
                        .progress(0.0)
                        .build();
            } else {
                responseDto = toHydrationDto(record);
            }
        }

        ApiMeta meta = ApiMeta.builder()
                .requestId(UUID.randomUUID().toString())
                .timestamp(Instant.now().toString())
                .source("api")
                .build();

        ApiResponse<HydrationRecordDTO> response = ApiResponse.<HydrationRecordDTO>builder()
                .data(responseDto)
                .meta(meta)
                .build();

        return ResponseEntity.ok(response);
    }

    @PutMapping("/hydration/{id}")
    public ResponseEntity<ApiResponse<HydrationRecordDTO>> updateHydration(
            @PathVariable String id,
            @Valid @RequestBody HydrationRequest request) {

        HydrationRecord existingRecord = hydrationRecordRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Hydration record not found with id: " + id));

        double target = request.getTargetMl() != null ? request.getTargetMl() : DEFAULT_TARGET_ML;

        existingRecord.setWaterIntakeMl(request.getWaterIntakeMl());
        existingRecord.setTargetMl(target);
        existingRecord.setNotes(request.getNotes());

        HydrationRecord updatedRecord = hydrationRecordRepository.save(existingRecord);
        HydrationRecordDTO responseDto = toHydrationDto(updatedRecord);

        ApiMeta meta = ApiMeta.builder()
                .requestId(UUID.randomUUID().toString())
                .timestamp(Instant.now().toString())
                .source("api")
                .build();

        ApiResponse<HydrationRecordDTO> response = ApiResponse.<HydrationRecordDTO>builder()
                .data(responseDto)
                .meta(meta)
                .build();

        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/hydration/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteHydration(@PathVariable String id) {
        
        if (!hydrationRecordRepository.existsById(id)) {
            throw new RuntimeException("Hydration record not found with id: " + id);
        }

        hydrationRecordRepository.deleteById(id);

        ApiMeta meta = ApiMeta.builder()
                .requestId(UUID.randomUUID().toString())
                .timestamp(Instant.now().toString())
                .source("api")
                .build();

        ApiResponse<Void> response = ApiResponse.<Void>builder()
                .data(null)
                .meta(meta)
                .build();

        return ResponseEntity.ok(response);
    }

    @PostMapping("/hydration/add")
    public ResponseEntity<ApiResponse<HydrationRecordDTO>> addWaterIntake(
            @RequestParam(value = "date", required = false) String dateStr,
            @RequestParam(value = "amount", required = true) Double amount) {

        LocalDate targetDate = dateStr != null && !dateStr.isEmpty()
                ? LocalDate.parse(dateStr, DATE_FORMATTER)
                : LocalDate.now();

        HydrationRecord existingRecord = hydrationRecordRepository.findFirstByDate(targetDate)
                .orElse(HydrationRecord.builder()
                        .date(targetDate)
                        .waterIntakeMl(0.0)
                        .targetMl(DEFAULT_TARGET_ML)
                        .build());

        existingRecord.setWaterIntakeMl(Math.max(0, existingRecord.getWaterIntakeMl() + amount));
        HydrationRecord savedRecord = hydrationRecordRepository.save(existingRecord);
        HydrationRecordDTO responseDto = toHydrationDto(savedRecord);

        ApiMeta meta = ApiMeta.builder()
                .requestId(UUID.randomUUID().toString())
                .timestamp(Instant.now().toString())
                .source("api")
                .build();

        ApiResponse<HydrationRecordDTO> response = ApiResponse.<HydrationRecordDTO>builder()
                .data(responseDto)
                .meta(meta)
                .build();

        return ResponseEntity.ok(response);
    }

    private HydrationRecordDTO toHydrationDto(HydrationRecord record) {
        double progress = record.getTargetMl() > 0 
                ? (record.getWaterIntakeMl() / record.getTargetMl()) * 100.0 
                : 0.0;
        progress = Math.min(progress, 100.0);

        return HydrationRecordDTO.builder()
                .id(record.getId())
                .date(record.getDate().format(DATE_FORMATTER))
                .waterIntakeMl(record.getWaterIntakeMl())
                .targetMl(record.getTargetMl())
                .progress(progress)
                .notes(record.getNotes())
                .build();
    }
}
