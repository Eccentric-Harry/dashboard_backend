package com.personal_dashboard.backend.controller;

import com.personal_dashboard.backend.dto.ApiResponse;
import com.personal_dashboard.backend.service.DashboardService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.UUID;
import java.time.Instant;

@RestController
@RequestMapping("/api/v1/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private final DashboardService dashboardService;

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final DateTimeFormatter MONTH_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM");

    @GetMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> getDashboard(
            @RequestParam(name = "date", required = false) String date) {

        LocalDate targetDate = date != null && !date.isBlank()
                ? LocalDate.parse(date, DATE_FORMATTER)
                : LocalDate.now();

        ApiResponse<Map<String, Object>> response = dashboardService.getDashboardData(targetDate);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/nutrition-summary")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getNutritionSummary(
            @RequestParam(name = "date", required = false) String date) {

        LocalDate targetDate = date != null && !date.isBlank()
                ? LocalDate.parse(date, DATE_FORMATTER)
                : LocalDate.now();

        Map<String, Object> nutritionData = dashboardService.getNutritionSummary(targetDate);

        ApiResponse<Map<String, Object>> response = ApiResponse.<Map<String, Object>>builder()
                .data(nutritionData)
                .meta(com.personal_dashboard.backend.dto.ApiMeta.builder()
                        .requestId(UUID.randomUUID().toString())
                        .timestamp(Instant.now().toString())
                        .source("api")
                        .build())
                .build();

        return ResponseEntity.ok(response);
    }

    @GetMapping("/spending-summary")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getSpendingSummary(
            @RequestParam(name = "month", required = false) String month) {

        YearMonth targetMonth = month != null && !month.isBlank()
                ? YearMonth.parse(month, MONTH_FORMATTER)
                : YearMonth.now();

        Map<String, Object> spendingData = dashboardService.getSpendingSummary(targetMonth);

        ApiResponse<Map<String, Object>> response = ApiResponse.<Map<String, Object>>builder()
                .data(spendingData)
                .meta(com.personal_dashboard.backend.dto.ApiMeta.builder()
                        .requestId(UUID.randomUUID().toString())
                        .timestamp(Instant.now().toString())
                        .source("api")
                        .build())
                .build();

        return ResponseEntity.ok(response);
    }
}
