package com.personal_dashboard.backend.controller;

import com.personal_dashboard.backend.dto.ApiMeta;
import com.personal_dashboard.backend.dto.ApiResponse;
import com.personal_dashboard.backend.dto.RingDayResponse;
import com.personal_dashboard.backend.dto.request.ManualMoveRequest;
import com.personal_dashboard.backend.model.DailyRing;
import com.personal_dashboard.backend.model.StreakState;
import com.personal_dashboard.backend.service.RingsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/rings")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Rings", description = "Three Non-Negotiables — daily rings, streaks, freezes, and XP")
public class RingsController {

    private final RingsService ringsService;
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE;

    @GetMapping("/today")
    @Operation(summary = "Today's rings", description = "The three rings for the current ring day, plus streak, XP, and level")
    public ResponseEntity<ApiResponse<RingDayResponse>> getToday() {
        log.debug("GET /rings/today");
        RingDayResponse today = ringsService.getToday();
        return ResponseEntity.ok(ApiResponse.<RingDayResponse>builder()
                .data(today)
                .meta(buildMeta("today"))
                .build());
    }

    @GetMapping("/range")
    @Operation(summary = "Ring days in a range", description = "Powers the journey map and week strips; recomputes stale days on read")
    public ResponseEntity<ApiResponse<List<DailyRing>>> getRange(
            @RequestParam(name = "startDate") String startDateStr,
            @RequestParam(name = "endDate") String endDateStr) {
        LocalDate startDate = LocalDate.parse(startDateStr, DATE_FORMATTER);
        LocalDate endDate = LocalDate.parse(endDateStr, DATE_FORMATTER);
        log.debug("GET /rings/range {} → {}", startDate, endDate);
        List<DailyRing> rings = ringsService.getRange(startDate, endDate);
        return ResponseEntity.ok(ApiResponse.<List<DailyRing>>builder()
                .data(rings)
                .meta(buildMeta("range"))
                .build());
    }

    @GetMapping("/streak")
    @Operation(summary = "Streak state", description = "Current and longest streak, freezes, total XP, and level")
    public ResponseEntity<ApiResponse<StreakState>> getStreak() {
        log.debug("GET /rings/streak");
        StreakState streak = ringsService.getStreak();
        return ResponseEntity.ok(ApiResponse.<StreakState>builder()
                .data(streak)
                .meta(buildMeta("streak"))
                .build());
    }

    @PostMapping("/move/manual")
    @Operation(summary = "Log a manual move", description = "The 'I moved' escape hatch for activity that never reached Strava")
    public ResponseEntity<ApiResponse<DailyRing>> logManualMove(@Valid @RequestBody ManualMoveRequest request) {
        DailyRing ring = ringsService.logManualMove(request);
        return ResponseEntity.ok(ApiResponse.<DailyRing>builder()
                .data(ring)
                .meta(buildMeta("move-manual"))
                .build());
    }

    @DeleteMapping("/move/manual")
    @Operation(summary = "Undo a manual move", description = "Remove the day's manual log and recompute the ring")
    public ResponseEntity<ApiResponse<DailyRing>> undoManualMove(@RequestParam(name = "date") String dateStr) {
        LocalDate date = LocalDate.parse(dateStr, DATE_FORMATTER);
        DailyRing ring = ringsService.undoManualMove(date);
        return ResponseEntity.ok(ApiResponse.<DailyRing>builder()
                .data(ring)
                .meta(buildMeta("move-manual-undo"))
                .build());
    }

    @PostMapping("/recompute")
    @Operation(summary = "Force a recompute", description = "Idempotent rebuild of one ring day — debug aid and post-backfill hook")
    public ResponseEntity<ApiResponse<DailyRing>> recompute(@RequestParam(name = "date") String dateStr) {
        LocalDate date = LocalDate.parse(dateStr, DATE_FORMATTER);
        log.info("POST /rings/recompute date={}", date);
        DailyRing ring = ringsService.recomputeDay(date);
        return ResponseEntity.ok(ApiResponse.<DailyRing>builder()
                .data(ring)
                .meta(buildMeta("recompute"))
                .build());
    }

    private ApiMeta buildMeta(String action) {
        return ApiMeta.builder()
                .requestId(UUID.randomUUID().toString())
                .timestamp(Instant.now().toString())
                .source("rings-" + action)
                .build();
    }
}
