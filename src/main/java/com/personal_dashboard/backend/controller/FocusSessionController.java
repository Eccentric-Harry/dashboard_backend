package com.personal_dashboard.backend.controller;

import com.personal_dashboard.backend.dto.ApiMeta;
import com.personal_dashboard.backend.dto.ApiResponse;
import com.personal_dashboard.backend.model.FocusSession;
import com.personal_dashboard.backend.service.FocusSessionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping({"/api/focus", "/api/v1/focus"})
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Focus Session", description = "Endpoints for managing Pomodoro focus sessions")
public class FocusSessionController {

    private final FocusSessionService service;

    @GetMapping("/current")
    @Operation(summary = "Get current focus session", description = "Returns the currently active or paused session")
    public ResponseEntity<ApiResponse<FocusSession>> getCurrentSession(
            @RequestParam(required = false, defaultValue = "default") String userId) {
        String activeUserId = com.personal_dashboard.backend.security.UserContext.getRequiredUserId();
        log.info("REST request to get current focus session for userId={}", activeUserId);
        var session = service.getCurrentSession(activeUserId);
        ApiResponse<FocusSession> response = ApiResponse.<FocusSession>builder()
                .data(session.orElse(null))
                .meta(buildMeta("current"))
                .build();
        return ResponseEntity.ok(response);
    }

    @GetMapping("/history")
    @Operation(summary = "Get focus history", description = "Completed focus minutes per day for dates in [startDate, endDate]")
    public ResponseEntity<ApiResponse<java.util.List<com.personal_dashboard.backend.dto.FocusDaySummary>>> getHistory(
            @RequestParam(name = "startDate") String startDate,
            @RequestParam(name = "endDate") String endDate) {
        String activeUserId = com.personal_dashboard.backend.security.UserContext.getRequiredUserId();
        var history = service.getDailyHistory(
                activeUserId, java.time.LocalDate.parse(startDate), java.time.LocalDate.parse(endDate));
        return ResponseEntity.ok(ApiResponse.<java.util.List<com.personal_dashboard.backend.dto.FocusDaySummary>>builder()
                .data(history)
                .meta(buildMeta("history"))
                .build());
    }

    @PostMapping("/log")
    @Operation(summary = "Log past focus", description = "Record focus work done away from the app (source=MANUAL)")
    public ResponseEntity<ApiResponse<FocusSession>> logPastSession(
            @jakarta.validation.Valid @RequestBody com.personal_dashboard.backend.dto.request.FocusLogRequest request) {
        String activeUserId = com.personal_dashboard.backend.security.UserContext.getRequiredUserId();
        log.info("REST request to log past focus: date={}, minutes={}", request.getDate(), request.getMinutes());
        FocusSession session = service.logPastSession(request, activeUserId);
        return ResponseEntity.status(org.springframework.http.HttpStatus.CREATED)
                .body(ApiResponse.<FocusSession>builder()
                        .data(session)
                        .meta(buildMeta("log"))
                        .build());
    }

    @DeleteMapping("/sessions/{id}")
    @Operation(summary = "Delete a focus session", description = "Undo a mistaken manual log or calendar import")
    public ResponseEntity<ApiResponse<Void>> deleteSession(@PathVariable String id) {
        String activeUserId = com.personal_dashboard.backend.security.UserContext.getRequiredUserId();
        service.deleteSession(id, activeUserId);
        return ResponseEntity.ok(ApiResponse.<Void>builder()
                .data(null)
                .meta(buildMeta("delete"))
                .build());
    }

    @GetMapping("/calendar-suggestions")
    @Operation(summary = "Calendar focus suggestions",
            description = "Timed calendar blocks (native + Google-synced) that look like focus work and are not yet imported")
    public ResponseEntity<ApiResponse<java.util.List<com.personal_dashboard.backend.dto.FocusSuggestion>>> getCalendarSuggestions(
            @RequestParam(name = "startDate") String startDate,
            @RequestParam(name = "endDate") String endDate) {
        String activeUserId = com.personal_dashboard.backend.security.UserContext.getRequiredUserId();
        var suggestions = service.getCalendarSuggestions(
                java.time.LocalDate.parse(startDate), java.time.LocalDate.parse(endDate), activeUserId);
        return ResponseEntity.ok(ApiResponse.<java.util.List<com.personal_dashboard.backend.dto.FocusSuggestion>>builder()
                .data(suggestions)
                .meta(buildMeta("calendar-suggestions"))
                .build());
    }

    @PostMapping("/import")
    @Operation(summary = "Import calendar blocks as focus",
            description = "Accept confirmed suggestions; durations are re-derived server-side and re-imports are ignored")
    public ResponseEntity<ApiResponse<java.util.List<FocusSession>>> importCalendarBlocks(
            @jakarta.validation.Valid @RequestBody com.personal_dashboard.backend.dto.request.FocusImportRequest request) {
        String activeUserId = com.personal_dashboard.backend.security.UserContext.getRequiredUserId();
        var imported = service.importCalendarBlocks(request, activeUserId);
        return ResponseEntity.status(org.springframework.http.HttpStatus.CREATED)
                .body(ApiResponse.<java.util.List<FocusSession>>builder()
                        .data(imported)
                        .meta(buildMeta("import"))
                        .build());
    }

    @PostMapping("/start")
    @Operation(summary = "Start a focus session", description = "Start a new focus session with pursuit and duration")
    public ResponseEntity<ApiResponse<FocusSession>> startSession(
            @RequestBody Map<String, Object> body,
            @RequestParam(required = false, defaultValue = "default") String userId) {
        String activeUserId = com.personal_dashboard.backend.security.UserContext.getRequiredUserId();
        String pursuit = (String) body.getOrDefault("activePursuit", "Coding");
        int duration = body.containsKey("durationMinutes") ? ((Number) body.get("durationMinutes")).intValue() : 25;

        log.info("REST request to start focus session: pursuit={}, duration={}m, userId={}", pursuit, duration, activeUserId);
        FocusSession session = service.startSession(pursuit, duration, activeUserId);
        ApiResponse<FocusSession> response = ApiResponse.<FocusSession>builder()
                .data(session)
                .meta(buildMeta("start"))
                .build();
        return ResponseEntity.ok(response);
    }

    @PostMapping("/pause")
    @Operation(summary = "Pause focus session", description = "Pause the currently running session")
    public ResponseEntity<ApiResponse<FocusSession>> pauseSession(
            @RequestParam(required = false, defaultValue = "default") String userId) {
        String activeUserId = com.personal_dashboard.backend.security.UserContext.getRequiredUserId();
        log.info("REST request to pause focus session for userId={}", activeUserId);
        FocusSession session = service.pauseSession(activeUserId);
        ApiResponse<FocusSession> response = ApiResponse.<FocusSession>builder()
                .data(session)
                .meta(buildMeta("pause"))
                .build();
        return ResponseEntity.ok(response);
    }

    @PostMapping("/resume")
    @Operation(summary = "Resume focus session", description = "Resume a paused session")
    public ResponseEntity<ApiResponse<FocusSession>> resumeSession(
            @RequestParam(required = false, defaultValue = "default") String userId) {
        String activeUserId = com.personal_dashboard.backend.security.UserContext.getRequiredUserId();
        log.info("REST request to resume focus session for userId={}", activeUserId);
        FocusSession session = service.resumeSession(activeUserId);
        ApiResponse<FocusSession> response = ApiResponse.<FocusSession>builder()
                .data(session)
                .meta(buildMeta("resume"))
                .build();
        return ResponseEntity.ok(response);
    }

    @PostMapping("/cancel")
    @Operation(summary = "Cancel focus session", description = "Cancel the active or paused session")
    public ResponseEntity<ApiResponse<FocusSession>> cancelSession(
            @RequestParam(required = false, defaultValue = "default") String userId) {
        String activeUserId = com.personal_dashboard.backend.security.UserContext.getRequiredUserId();
        log.info("REST request to cancel focus session for userId={}", activeUserId);
        FocusSession session = service.cancelSession(activeUserId);
        ApiResponse<FocusSession> response = ApiResponse.<FocusSession>builder()
                .data(session)
                .meta(buildMeta("cancel"))
                .build();
        return ResponseEntity.ok(response);
    }

    @PostMapping("/complete")
    @Operation(summary = "Complete focus session", description = "Mark the running session as completed")
    public ResponseEntity<ApiResponse<FocusSession>> completeSession(
            @RequestParam(required = false, defaultValue = "default") String userId) {
        String activeUserId = com.personal_dashboard.backend.security.UserContext.getRequiredUserId();
        log.info("REST request to complete focus session for userId={}", activeUserId);
        FocusSession session = service.completeSession(activeUserId);
        ApiResponse<FocusSession> response = ApiResponse.<FocusSession>builder()
                .data(session)
                .meta(buildMeta("complete"))
                .build();
        return ResponseEntity.ok(response);
    }

    private ApiMeta buildMeta(String action) {
        return ApiMeta.builder()
                .requestId(UUID.randomUUID().toString())
                .timestamp(Instant.now().toString())
                .source("focus-" + action)
                .build();
    }
}
