package com.personal_dashboard.backend.controller;

import com.personal_dashboard.backend.dto.ApiMeta;
import com.personal_dashboard.backend.dto.ApiResponse;
import com.personal_dashboard.backend.dto.request.DailyLogRequest;
import com.personal_dashboard.backend.model.DailyLog;
import com.personal_dashboard.backend.service.DailyLogService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/daily-log")
@RequiredArgsConstructor
@Tag(name = "Daily Log", description = "Daily focus and coding counters")
public class DailyLogController {

    private final DailyLogService dailyLogService;
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE;

    @GetMapping
    @Operation(summary = "Get daily log", description = "Returns daily log for date or empty shell")
    public ResponseEntity<ApiResponse<DailyLog>> getDailyLog(
            @RequestParam(name = "date") String dateStr) {
        LocalDate date = LocalDate.parse(dateStr, DATE_FORMATTER);
        DailyLog log = dailyLogService.getOrEmptyForDate(date);
        return ResponseEntity.ok(ApiResponse.<DailyLog>builder()
                .data(log)
                .meta(buildMeta("fetch"))
                .build());
    }

    @GetMapping("/range")
    @Operation(summary = "Get daily logs in range", description = "Returns existing daily logs (mood check-ins) with date in [startDate, endDate]")
    public ResponseEntity<ApiResponse<java.util.List<DailyLog>>> getDailyLogRange(
            @RequestParam(name = "startDate") String startDateStr,
            @RequestParam(name = "endDate") String endDateStr) {
        LocalDate startDate = LocalDate.parse(startDateStr, DATE_FORMATTER);
        LocalDate endDate = LocalDate.parse(endDateStr, DATE_FORMATTER);
        return ResponseEntity.ok(ApiResponse.<java.util.List<DailyLog>>builder()
                .data(dailyLogService.getRange(startDate, endDate))
                .meta(buildMeta("range"))
                .build());
    }

    @PutMapping
    @Operation(summary = "Upsert daily log", description = "Update daily focus and optional coding counters")
    public ResponseEntity<ApiResponse<DailyLog>> upsertDailyLog(
            @RequestParam(name = "date") String dateStr,
            @Valid @RequestBody DailyLogRequest request) {
        LocalDate date = LocalDate.parse(dateStr, DATE_FORMATTER);
        DailyLog updated = dailyLogService.upsertForDate(date, request);
        return ResponseEntity.ok(ApiResponse.<DailyLog>builder()
                .data(updated)
                .meta(buildMeta("upsert"))
                .build());
    }

    private ApiMeta buildMeta(String action) {
        return ApiMeta.builder()
                .requestId(UUID.randomUUID().toString())
                .timestamp(Instant.now().toString())
                .source("daily-log-" + action)
                .build();
    }
}
