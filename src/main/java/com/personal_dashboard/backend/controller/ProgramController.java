package com.personal_dashboard.backend.controller;

import com.personal_dashboard.backend.dto.ApiMeta;
import com.personal_dashboard.backend.dto.ApiResponse;
import com.personal_dashboard.backend.dto.ProgramMediaView;
import com.personal_dashboard.backend.dto.ProgramStateResponse;
import com.personal_dashboard.backend.dto.ProgramView;
import com.personal_dashboard.backend.dto.request.ProgramAssessmentRequest;
import com.personal_dashboard.backend.dto.request.ProgramLetterRequest;
import com.personal_dashboard.backend.dto.request.ProgramLogRequest;
import com.personal_dashboard.backend.dto.request.ProgramMediaRequest;
import com.personal_dashboard.backend.dto.request.ProgramReviewRequest;
import com.personal_dashboard.backend.dto.request.ProgramSettingsRequest;
import com.personal_dashboard.backend.dto.request.ProgramStartRequest;
import com.personal_dashboard.backend.model.ProgramAssessment;
import com.personal_dashboard.backend.model.ProgramLog;
import com.personal_dashboard.backend.service.ProgramService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Programs on /goals — "90 days to 23", lived in The Lighthouse world. The client derives
 * every view from GET /program; the rest store one thing each.
 */
@RestController
@RequestMapping("/api/v1/program")
@RequiredArgsConstructor
@Tag(name = "Program", description = "A fixed-length program of tracks, reviews and check-ins (The Lighthouse on /goals)")
public class ProgramController {

    private final ProgramService programService;

    @GetMapping
    @Operation(summary = "Program state", description = "The active program (null when none) with its logs, reviews, check-ins, media list and other routes' data for its days")
    public ResponseEntity<ApiResponse<ProgramStateResponse>> getState(@RequestParam(name = "today", required = false) String today) {
        return ok(programService.getState(today == null || today.isBlank() ? null : LocalDate.parse(today)), "state");
    }

    @PostMapping
    @Operation(summary = "Begin a program", description = "Seeds the eight tracks; one active program at a time")
    public ResponseEntity<ApiResponse<ProgramView>> start(@Valid @RequestBody ProgramStartRequest request) {
        return ok(programService.start(request), "start");
    }

    @PutMapping("/{id}")
    @Operation(summary = "Program settings", description = "Everything except targets — those change only in the weekly review")
    public ResponseEntity<ApiResponse<ProgramView>> updateSettings(@PathVariable String id, @Valid @RequestBody ProgramSettingsRequest request) {
        return ok(programService.updateSettings(id, request), "settings");
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Start over", description = "Deletes the program and everything logged on it")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable String id) {
        programService.delete(id);
        return ok(null, "delete");
    }

    @PutMapping("/{id}/letters/{key}")
    @Operation(summary = "Write a letter", description = "to23 seals until the birthday, to24 for a year after; from23 is always readable")
    public ResponseEntity<ApiResponse<ProgramView>> writeLetter(@PathVariable String id, @PathVariable String key, @Valid @RequestBody ProgramLetterRequest request) {
        return ok(programService.writeLetter(id, key, request), "letter");
    }

    @PostMapping("/{id}/logs")
    @Operation(summary = "Log an action on a track")
    public ResponseEntity<ApiResponse<ProgramLog>> addLog(@PathVariable String id, @Valid @RequestBody ProgramLogRequest request) {
        return ok(programService.addLog(id, request), "log");
    }

    @PutMapping("/{id}/logs/{logId}")
    @Operation(summary = "Edit a log")
    public ResponseEntity<ApiResponse<ProgramLog>> updateLog(@PathVariable String id, @PathVariable String logId, @Valid @RequestBody ProgramLogRequest request) {
        return ok(programService.updateLog(id, logId, request), "log-update");
    }

    @DeleteMapping("/{id}/logs/{logId}")
    @Operation(summary = "Undo a log")
    public ResponseEntity<ApiResponse<Void>> deleteLog(@PathVariable String id, @PathVariable String logId) {
        programService.deleteLog(id, logId);
        return ok(null, "log-delete");
    }

    @PutMapping("/{id}/reviews/{weekStart}")
    @Operation(summary = "Save the weekly review", description = "Self-trust, one win, one obstacle, one adjustment — and the only way to change targets")
    public ResponseEntity<ApiResponse<ProgramService.ReviewResult>> saveReview(
            @PathVariable String id,
            @PathVariable String weekStart,
            @Valid @RequestBody ProgramReviewRequest request) {
        return ok(programService.saveReview(id, LocalDate.parse(weekStart), request), "review");
    }

    @PostMapping("/{id}/assessments")
    @Operation(summary = "Record a check-in", description = "Rosenberg and WHO-5 are scored here; BODY holds photos, weight and waist")
    public ResponseEntity<ApiResponse<ProgramAssessment>> addAssessment(@PathVariable String id, @Valid @RequestBody ProgramAssessmentRequest request) {
        return ok(programService.addAssessment(id, request), "assessment");
    }

    @DeleteMapping("/{id}/assessments/{assessmentId}")
    @Operation(summary = "Delete a check-in")
    public ResponseEntity<ApiResponse<Void>> deleteAssessment(@PathVariable String id, @PathVariable String assessmentId) {
        programService.deleteAssessment(id, assessmentId);
        return ok(null, "assessment-delete");
    }

    @PostMapping("/{id}/media")
    @Operation(summary = "Save a photo or recording", description = "A data: URL; photos up to 1.5 MB, recordings up to 4 MB")
    public ResponseEntity<ApiResponse<ProgramMediaView>> addMedia(@PathVariable String id, @Valid @RequestBody ProgramMediaRequest request) {
        return ok(programService.addMedia(id, request), "media");
    }

    @GetMapping("/{id}/media/{mediaId}")
    @Operation(summary = "Fetch one photo or recording", description = "Returned as a data: URL, only to its owner")
    public ResponseEntity<ApiResponse<ProgramMediaView>> getMedia(@PathVariable String id, @PathVariable String mediaId) {
        return ok(programService.getMedia(id, mediaId), "media-get");
    }

    @DeleteMapping("/{id}/media/{mediaId}")
    @Operation(summary = "Delete a photo or recording")
    public ResponseEntity<ApiResponse<Void>> deleteMedia(@PathVariable String id, @PathVariable String mediaId) {
        programService.deleteMedia(id, mediaId);
        return ok(null, "media-delete");
    }

    private static <T> ResponseEntity<ApiResponse<T>> ok(T data, String action) {
        return ResponseEntity.ok(ApiResponse.<T>builder()
                .data(data)
                .meta(ApiMeta.builder()
                        .requestId(UUID.randomUUID().toString())
                        .timestamp(Instant.now().toString())
                        .source("program-" + action)
                        .build())
                .build());
    }
}
