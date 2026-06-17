package com.personal_dashboard.backend.controller;

import com.personal_dashboard.backend.dto.ApiMeta;
import com.personal_dashboard.backend.dto.ApiResponse;
import com.personal_dashboard.backend.dto.CalendarItemOccurrence;
import com.personal_dashboard.backend.dto.request.CalendarItemRequest;
import com.personal_dashboard.backend.model.DailyTask;
import com.personal_dashboard.backend.service.CalendarItemService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/calendar/items")
@RequiredArgsConstructor
@Tag(name = "Calendar", description = "Calendar schedule items and expanded occurrences")
public class CalendarController {

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE;
    private final CalendarItemService calendarItemService;

    @GetMapping("/range")
    @Operation(summary = "Get calendar occurrences", description = "Fetch one-off and recurring item occurrences in a date range")
    public ResponseEntity<ApiResponse<List<CalendarItemOccurrence>>> getOccurrences(
            @RequestParam String startDate,
            @RequestParam String endDate) {
        LocalDate start = LocalDate.parse(startDate, DATE_FORMATTER);
        LocalDate end = LocalDate.parse(endDate, DATE_FORMATTER);
        return ResponseEntity.ok(ApiResponse.<List<CalendarItemOccurrence>>builder()
                .data(calendarItemService.getOccurrences(start, end))
                .meta(buildMeta("range"))
                .build());
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get calendar item", description = "Fetch a single source calendar item")
    public ResponseEntity<ApiResponse<DailyTask>> getItem(@PathVariable String id) {
        return ResponseEntity.ok(ApiResponse.<DailyTask>builder()
                .data(calendarItemService.getItem(id))
                .meta(buildMeta("fetch"))
                .build());
    }

    @PostMapping
    @Operation(summary = "Create calendar item", description = "Create a schedule item, task, reminder, event, or milestone")
    public ResponseEntity<ApiResponse<DailyTask>> createItem(@Valid @RequestBody CalendarItemRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.<DailyTask>builder()
                .data(calendarItemService.createItem(request))
                .meta(buildMeta("create"))
                .build());
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update calendar item", description = "Update a source calendar item")
    public ResponseEntity<ApiResponse<DailyTask>> updateItem(
            @PathVariable String id,
            @Valid @RequestBody CalendarItemRequest request) {
        return ResponseEntity.ok(ApiResponse.<DailyTask>builder()
                .data(calendarItemService.updateItem(id, request))
                .meta(buildMeta("update"))
                .build());
    }

    @PatchMapping("/{id}/toggle")
    @Operation(summary = "Toggle completion", description = "Toggle completion for task-like calendar items")
    public ResponseEntity<ApiResponse<DailyTask>> toggleItem(
            @PathVariable String id,
            @RequestParam(required = false) String date) {
        LocalDate occurrenceDate = (date != null && !date.isBlank()) ? LocalDate.parse(date, DATE_FORMATTER) : null;
        return ResponseEntity.ok(ApiResponse.<DailyTask>builder()
                .data(calendarItemService.toggleItem(id, occurrenceDate))
                .meta(buildMeta("toggle"))
                .build());
    }

    @PatchMapping("/{id}/toggle-cancel")
    @Operation(summary = "Toggle cancellation", description = "Toggle cancellation for calendar items")
    public ResponseEntity<ApiResponse<DailyTask>> toggleCancelItem(
            @PathVariable String id,
            @RequestParam(required = false) String date) {
        LocalDate occurrenceDate = (date != null && !date.isBlank()) ? LocalDate.parse(date, DATE_FORMATTER) : null;
        return ResponseEntity.ok(ApiResponse.<DailyTask>builder()
                .data(calendarItemService.toggleCancelItem(id, occurrenceDate))
                .meta(buildMeta("toggle-cancel"))
                .build());
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete calendar item", description = "Delete a source calendar item or occurrence")
    public ResponseEntity<ApiResponse<Void>> deleteItem(
            @PathVariable String id,
            @RequestParam(required = false) String date) {
        if (date != null && !date.isBlank()) {
            calendarItemService.deleteOccurrence(id, LocalDate.parse(date, DATE_FORMATTER));
        } else {
            calendarItemService.deleteItem(id);
        }
        return ResponseEntity.ok(ApiResponse.<Void>builder()
                .data(null)
                .meta(buildMeta("delete"))
                .build());
    }

    private ApiMeta buildMeta(String action) {
        return ApiMeta.builder()
                .requestId(UUID.randomUUID().toString())
                .timestamp(Instant.now().toString())
                .source("calendar-items-" + action)
                .build();
    }
}
