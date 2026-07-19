package com.personal_dashboard.backend.controller;

import com.personal_dashboard.backend.dto.ApiMeta;
import com.personal_dashboard.backend.dto.ApiResponse;
import com.personal_dashboard.backend.dto.MindSummaryResponse;
import com.personal_dashboard.backend.dto.request.MindEntryRequest;
import com.personal_dashboard.backend.dto.request.MindMoodRequest;
import com.personal_dashboard.backend.dto.request.MindStatusRequest;
import com.personal_dashboard.backend.model.DailyLog;
import com.personal_dashboard.backend.model.MindEntry;
import com.personal_dashboard.backend.service.MindService;
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
@RequestMapping("/api/v1/mind")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Mind", description = "Mental wellness — thought capture, triage, mood, and evidence")
public class MindController {

    private final MindService mindService;
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE;

    @GetMapping("/entries")
    @Operation(summary = "List mind entries", description = "Fetch entries, optionally filtered by type and status")
    public ResponseEntity<ApiResponse<List<MindEntry>>> getEntries(
            @RequestParam(name = "type", required = false) String type,
            @RequestParam(name = "status", required = false) String status) {
        log.debug("GET /mind/entries type={} status={}", type, status);
        List<MindEntry> entries = mindService.getEntries(type, status);
        return ResponseEntity.ok(ApiResponse.<List<MindEntry>>builder()
                .data(entries)
                .meta(buildMeta("fetch"))
                .build());
    }

    @PostMapping("/entries")
    @Operation(summary = "Capture a mind entry", description = "Add a thought, win, or gratitude note")
    public ResponseEntity<ApiResponse<MindEntry>> createEntry(@Valid @RequestBody MindEntryRequest request) {
        MindEntry created = mindService.createEntry(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.<MindEntry>builder()
                .data(created)
                .meta(buildMeta("create"))
                .build());
    }

    @PutMapping("/entries/{id}")
    @Operation(summary = "Update a mind entry", description = "Edit text, type, value tag, or pin")
    public ResponseEntity<ApiResponse<MindEntry>> updateEntry(
            @PathVariable String id,
            @Valid @RequestBody MindEntryRequest request) {
        MindEntry updated = mindService.updateEntry(id, request);
        return ResponseEntity.ok(ApiResponse.<MindEntry>builder()
                .data(updated)
                .meta(buildMeta("update"))
                .build());
    }

    @PatchMapping("/entries/{id}/status")
    @Operation(summary = "Triage a mind entry", description = "Park, release, reframe, or bring back a thought")
    public ResponseEntity<ApiResponse<MindEntry>> updateStatus(
            @PathVariable String id,
            @Valid @RequestBody MindStatusRequest request) {
        MindEntry updated = mindService.updateStatus(id, request);
        return ResponseEntity.ok(ApiResponse.<MindEntry>builder()
                .data(updated)
                .meta(buildMeta("status"))
                .build());
    }

    @PostMapping("/entries/{id}/convert")
    @Operation(summary = "Convert a thought to a task", description = "The 'Do' action — creates a DailyTask and links it")
    public ResponseEntity<ApiResponse<MindEntry>> convertToTask(@PathVariable String id) {
        MindEntry converted = mindService.convertToTask(id);
        return ResponseEntity.ok(ApiResponse.<MindEntry>builder()
                .data(converted)
                .meta(buildMeta("convert"))
                .build());
    }

    @DeleteMapping("/entries/{id}")
    @Operation(summary = "Delete a mind entry")
    public ResponseEntity<ApiResponse<Void>> deleteEntry(@PathVariable String id) {
        mindService.deleteEntry(id);
        return ResponseEntity.ok(ApiResponse.<Void>builder()
                .data(null)
                .meta(buildMeta("delete"))
                .build());
    }

    @PutMapping("/mood")
    @Operation(summary = "Save mood check-in", description = "Persist a 1–5 mood score onto the day's log")
    public ResponseEntity<ApiResponse<DailyLog>> saveMood(
            @RequestParam(name = "date") String dateStr,
            @Valid @RequestBody MindMoodRequest request) {
        LocalDate date = LocalDate.parse(dateStr, DATE_FORMATTER);
        DailyLog updated = mindService.saveMood(date, request);
        return ResponseEntity.ok(ApiResponse.<DailyLog>builder()
                .data(updated)
                .meta(buildMeta("mood"))
                .build());
    }

    @GetMapping("/summary")
    @Operation(summary = "Mind summary", description = "Auto-evidence, streak, loop stats, and today's mood")
    public ResponseEntity<ApiResponse<MindSummaryResponse>> getSummary(
            @RequestParam(name = "date", required = false) String dateStr) {
        LocalDate date = (dateStr != null && !dateStr.isBlank())
                ? LocalDate.parse(dateStr, DATE_FORMATTER)
                : null;
        log.debug("GET /mind/summary date={}", date);
        MindSummaryResponse summary = mindService.getSummary(date);
        return ResponseEntity.ok(ApiResponse.<MindSummaryResponse>builder()
                .data(summary)
                .meta(buildMeta("summary"))
                .build());
    }

    private ApiMeta buildMeta(String action) {
        return ApiMeta.builder()
                .requestId(UUID.randomUUID().toString())
                .timestamp(Instant.now().toString())
                .source("mind-" + action)
                .build();
    }
}
