package com.personal_dashboard.backend.controller;

import com.personal_dashboard.backend.dto.ApiMeta;
import com.personal_dashboard.backend.dto.ApiResponse;
import com.personal_dashboard.backend.dto.request.SleepLogRequest;
import com.personal_dashboard.backend.model.SleepLog;
import com.personal_dashboard.backend.service.SleepService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/v1/sleep")
@RequiredArgsConstructor
@Tag(name = "Sleep", description = "Nightly sleep entries (one per user per wake-up date)")
public class SleepController {

    private final SleepService sleepService;

    @GetMapping
    @Operation(summary = "List sleep entries", description = "Returns entries with date in [startDate, endDate], oldest first")
    public ResponseEntity<ApiResponse<List<SleepLog>>> getSleepEntries(
            @RequestParam(name = "startDate") String startDate,
            @RequestParam(name = "endDate") String endDate) {
        log.debug("GET /sleep {} to {}", startDate, endDate);
        List<SleepLog> entries = sleepService.getRange(LocalDate.parse(startDate), LocalDate.parse(endDate));
        return ResponseEntity.ok(ApiResponse.<List<SleepLog>>builder()
                .data(entries)
                .meta(buildMeta("range"))
                .build());
    }

    @PostMapping
    @Operation(summary = "Log sleep", description = "Creates the night's entry, or updates it if that date is already logged")
    public ResponseEntity<ApiResponse<SleepLog>> logSleep(@Valid @RequestBody SleepLogRequest request) {
        SleepLog entry = sleepService.logSleep(request);
        return ResponseEntity.ok(ApiResponse.<SleepLog>builder()
                .data(entry)
                .meta(buildMeta("log"))
                .build());
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update sleep entry")
    public ResponseEntity<ApiResponse<SleepLog>> updateSleep(
            @PathVariable String id,
            @Valid @RequestBody SleepLogRequest request) {
        SleepLog entry = sleepService.updateEntry(id, request);
        return ResponseEntity.ok(ApiResponse.<SleepLog>builder()
                .data(entry)
                .meta(buildMeta("update"))
                .build());
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete sleep entry")
    public ResponseEntity<ApiResponse<Void>> deleteSleep(@PathVariable String id) {
        sleepService.deleteEntry(id);
        return ResponseEntity.ok(ApiResponse.<Void>builder()
                .data(null)
                .meta(buildMeta("delete"))
                .build());
    }

    private ApiMeta buildMeta(String action) {
        return ApiMeta.builder()
                .requestId(UUID.randomUUID().toString())
                .timestamp(Instant.now().toString())
                .source("sleep-" + action)
                .build();
    }
}
