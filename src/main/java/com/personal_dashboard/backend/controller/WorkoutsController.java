package com.personal_dashboard.backend.controller;

import com.personal_dashboard.backend.dto.ApiMeta;
import com.personal_dashboard.backend.dto.ApiResponse;
import com.personal_dashboard.backend.dto.WorkoutsMetrics;
import com.personal_dashboard.backend.service.WorkoutsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/workouts")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Workouts", description = "Workouts RDH integration endpoints")
public class WorkoutsController {

    private final WorkoutsService workoutsService;

    /**
     * Manually push workouts data to RDH Cache
     */
    @PostMapping("/data")
    @Operation(summary = "Update Workouts Data", description = "Manually push JSON data to the RDH Cache so the UI can consume it.")
    @ApiResponses(value = {
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Successfully synced and cached data"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "Server error")
    })
    public ResponseEntity<?> updateWorkoutsData(@RequestBody WorkoutsMetrics metrics) {
        try {
            log.info("Updating Workouts Data in RDH Cache");

            WorkoutsMetrics updatedMetrics = workoutsService.updateWorkoutsData(metrics);

            ApiMeta meta = ApiMeta.builder()
                    .requestId(UUID.randomUUID().toString())
                    .timestamp(Instant.now().toString())
                    .source("rdh-upload")
                    .build();

            ApiResponse<WorkoutsMetrics> response = ApiResponse.<WorkoutsMetrics>builder()
                    .data(updatedMetrics)
                    .meta(meta)
                    .build();

            log.info("Successfully updated workouts data");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Error updating workouts data", e);
            ApiResponse<?> errorResponse = buildErrorResponse("Update failed: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
        }
    }

    /**
     * Get workouts data from RDH Cache
     */
    @GetMapping
    @Operation(summary = "Get workouts dashboard", description = "Fetch workouts data from local RDH Cache.")
    @ApiResponses(value = {
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Successfully retrieved cached workouts data")
    })
    public ResponseEntity<?> getWorkoutsData() {
        try {
            log.info("Fetching cached workouts data");

            WorkoutsMetrics metrics = workoutsService.getCachedWorkoutsData();

            ApiMeta meta = ApiMeta.builder()
                    .requestId(UUID.randomUUID().toString())
                    .timestamp(Instant.now().toString())
                    .source("rdh-cache")
                    .build();

            ApiResponse<WorkoutsMetrics> response = ApiResponse.<WorkoutsMetrics>builder()
                    .data(metrics)
                    .meta(meta)
                    .build();

            log.info("Successfully returned cached workouts data");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Error fetching workouts data", e);
            ApiResponse<?> errorResponse = buildErrorResponse(e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
        }
    }

    /**
     * Get recent activities with optional limit parameter
     */
    @GetMapping("/recent")
    @Operation(summary = "Get recent activities", description = "Fetch recent workouts from RDH Cache with an optional limit parameter")
    public ResponseEntity<?> getRecentActivities(
            @RequestParam(defaultValue = "20") int limit) {
        try {
            log.info("Fetching recent activities with limit: {}", limit);

            List<WorkoutsMetrics.ActivitySummary> activities = workoutsService.getRecentActivities(limit);

            ApiMeta meta = ApiMeta.builder()
                    .requestId(UUID.randomUUID().toString())
                    .timestamp(Instant.now().toString())
                    .source("rdh-cache")
                    .build();

            ApiResponse<List<WorkoutsMetrics.ActivitySummary>> response = ApiResponse.<List<WorkoutsMetrics.ActivitySummary>>builder()
                    .data(activities)
                    .meta(meta)
                    .build();

            log.info("Successfully returned {} recent activities", activities.size());
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Error fetching recent activities", e);
            ApiResponse<?> errorResponse = ApiResponse.builder()
                    .data(null)
                    .meta(buildErrorMeta())
                    .build();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
        }
    }

    /**
     * Get all activities
     */
    @GetMapping("/all")
    @Operation(summary = "Get all activities", description = "Fetch all activities from RDH Cache")
    public ResponseEntity<?> getAllActivities() {
        try {
            log.info("Fetching all activities");

            List<WorkoutsMetrics.ActivitySummary> activities = workoutsService.getAllActivities();

            ApiMeta meta = ApiMeta.builder()
                    .requestId(UUID.randomUUID().toString())
                    .timestamp(Instant.now().toString())
                    .source("rdh-cache")
                    .build();

            ApiResponse<List<WorkoutsMetrics.ActivitySummary>> response = ApiResponse.<List<WorkoutsMetrics.ActivitySummary>>builder()
                    .data(activities)
                    .meta(meta)
                    .build();

            log.info("Successfully returned {} total activities", activities.size());
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Error fetching all activities", e);
            ApiResponse<?> errorResponse = ApiResponse.builder()
                    .data(null)
                    .meta(buildErrorMeta())
                    .build();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
        }
    }

    private ApiResponse<?> buildErrorResponse(String message) {
        ApiMeta meta = ApiMeta.builder()
                .requestId(UUID.randomUUID().toString())
                .timestamp(Instant.now().toString())
                .source("rdh-cache")
                .build();

        return ApiResponse.builder()
                .data(null)
                .meta(meta)
                .build();
    }

    private ApiMeta buildErrorMeta() {
        return ApiMeta.builder()
                .requestId(UUID.randomUUID().toString())
                .timestamp(Instant.now().toString())
                .source("rdh-cache")
                .build();
    }
}
