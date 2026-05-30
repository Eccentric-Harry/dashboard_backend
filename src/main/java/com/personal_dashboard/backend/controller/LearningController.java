package com.personal_dashboard.backend.controller;

import com.personal_dashboard.backend.dto.ApiMeta;
import com.personal_dashboard.backend.dto.ApiResponse;
import com.personal_dashboard.backend.dto.request.LearningRequest;
import com.personal_dashboard.backend.model.Learning;
import com.personal_dashboard.backend.service.LearningService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/learnings")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Learnings", description = "Endpoints for managing learning logs")
public class LearningController {

    private final LearningService learningService;
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE;

    @GetMapping
    @Operation(summary = "Get learnings", description = "Fetch all learnings or filter by a specific date")
    public ResponseEntity<ApiResponse<List<Learning>>> getLearnings(
            @RequestParam(name = "date", required = false) String dateStr) {
        
        List<Learning> learnings;
        if (dateStr != null && !dateStr.isBlank()) {
            LocalDate date = LocalDate.parse(dateStr, DATE_FORMATTER);
            learnings = learningService.getLearningsForDate(date);
        } else {
            learnings = learningService.getAllLearnings();
        }

        ApiResponse<List<Learning>> response = ApiResponse.<List<Learning>>builder()
                .data(learnings)
                .meta(buildMeta("fetch"))
                .build();
        return ResponseEntity.ok(response);
    }

    @GetMapping("/range")
    @Operation(summary = "Get learnings for range", description = "Fetch learnings between start and end dates")
    public ResponseEntity<ApiResponse<List<Learning>>> getLearningsRange(
            @RequestParam(name = "startDate") String startDateStr,
            @RequestParam(name = "endDate") String endDateStr) {
        
        LocalDate startDate = LocalDate.parse(startDateStr, DATE_FORMATTER);
        LocalDate endDate = LocalDate.parse(endDateStr, DATE_FORMATTER);
        List<Learning> learnings = learningService.getLearningsForRange(startDate, endDate);

        ApiResponse<List<Learning>> response = ApiResponse.<List<Learning>>builder()
                .data(learnings)
                .meta(buildMeta("range"))
                .build();
        return ResponseEntity.ok(response);
    }

    @PostMapping
    @Operation(summary = "Create learning log", description = "Add a new learning log")
    public ResponseEntity<ApiResponse<Learning>> createLearning(
            @Valid @RequestBody LearningRequest request) {
        
        log.info("REST request to save learning: {}", request.getTitle());
        Learning created = learningService.createLearning(request);

        ApiResponse<Learning> response = ApiResponse.<Learning>builder()
                .data(created)
                .meta(buildMeta("create"))
                .build();
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update learning log", description = "Update an existing learning log by id")
    public ResponseEntity<ApiResponse<Learning>> updateLearning(
            @PathVariable String id,
            @Valid @RequestBody LearningRequest request) {
        
        log.info("REST request to update learning: {}, id: {}", request.getTitle(), id);
        Learning updated = learningService.updateLearning(id, request);

        ApiResponse<Learning> response = ApiResponse.<Learning>builder()
                .data(updated)
                .meta(buildMeta("update"))
                .build();
        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete learning log", description = "Delete a learning log by id")
    public ResponseEntity<ApiResponse<Void>> deleteLearning(@PathVariable String id) {
        log.info("REST request to delete learning: {}", id);
        learningService.deleteLearning(id);

        ApiResponse<Void> response = ApiResponse.<Void>builder()
                .data(null)
                .meta(buildMeta("delete"))
                .build();
        return ResponseEntity.ok(response);
    }

    private ApiMeta buildMeta(String action) {
        return ApiMeta.builder()
                .requestId(UUID.randomUUID().toString())
                .timestamp(Instant.now().toString())
                .source("learnings-" + action)
                .build();
    }
}
