package com.personal_dashboard.backend.controller;

import com.personal_dashboard.backend.dto.ApiMeta;
import com.personal_dashboard.backend.dto.ApiResponse;
import com.personal_dashboard.backend.dto.MealAnalysisJobDTO;
import com.personal_dashboard.backend.model.MealAnalysisJob;
import com.personal_dashboard.backend.security.UserContext;
import com.personal_dashboard.backend.service.MealAnalysisJobService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * REST controller for the asynchronous Gemini meal analysis pipeline.
 *
 * <p>The pipeline is long-running (two Gemini vision calls plus image generation),
 * so it is exposed as a job:
 * <ul>
 *   <li>{@code POST /api/v1/meals/analyze} — validate, enqueue the work, and return
 *       a job id immediately (HTTP 202). The response is instantaneous, so it can
 *       never be lost to a proxy read-timeout.</li>
 *   <li>{@code GET /api/v1/meals/analyze/{jobId}} — poll for status; when
 *       {@code COMPLETED} the full analysis is returned, when {@code FAILED} a
 *       machine-readable {@code errorSource} explains why.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/meals")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Meal Analysis", description = "AI-powered two-stage meal nutrition pipeline via Gemini")
public class MealAnalysisController {

    private final MealAnalysisJobService jobService;

    /** Maximum images accepted per meal scan. */
    private static final int MAX_IMAGES = 3;

    /**
     * Start an asynchronous meal analysis. Reads the uploaded images on the request
     * thread (multipart data is not readable once the request returns), enqueues the
     * job, and returns its id so the client can poll for the result.
     */
    @PostMapping(value = "/analyze", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(
            summary = "Start AI Meal Analysis (async)",
            description = "Validates input, enqueues the two-stage Gemini pipeline on a background worker, and returns a jobId immediately. Poll GET /meals/analyze/{jobId} for the result.")
    public ResponseEntity<ApiResponse<MealAnalysisJobDTO>> analyzeMeal(
            @RequestPart(value = "files", required = false) List<MultipartFile> files,
            @RequestPart(value = "file", required = false) MultipartFile file,
            @RequestParam(value = "description", required = false) String description,
            @RequestParam("mealType") String mealType,
            @RequestParam(value = "date", required = false) String date) {

        // ── Collect images (multi-image "files" part, plus legacy single "file") ──
        List<MultipartFile> images = new ArrayList<>();
        if (files != null) {
            for (MultipartFile f : files) {
                if (f != null && !f.isEmpty()) images.add(f);
            }
        }
        if (file != null && !file.isEmpty()) images.add(file);
        if (images.size() > MAX_IMAGES) {
            log.warn("[MealAnalysis] {} images supplied; using the first {}", images.size(), MAX_IMAGES);
            images = images.subList(0, MAX_IMAGES);
        }

        // ── Validation ──────────────────────────────────────────────────
        boolean hasImage = !images.isEmpty();
        boolean hasText = description != null && !description.isBlank();

        if (!hasImage && !hasText) {
            return ResponseEntity.badRequest().body(ApiResponse.<MealAnalysisJobDTO>builder()
                    .meta(buildMeta("validation-error"))
                    .build());
        }

        String validatedMealType = validateMealType(mealType);
        if (validatedMealType == null) {
            return ResponseEntity.badRequest().body(ApiResponse.<MealAnalysisJobDTO>builder()
                    .meta(buildMeta("validation-error"))
                    .build());
        }

        // ── Read image bytes now, on the request thread ─────────────────
        List<byte[]> imageBytes = new ArrayList<>();
        try {
            for (MultipartFile f : images) {
                imageBytes.add(f.getBytes());
            }
        } catch (IOException e) {
            log.error("[MealAnalysis] Failed to read uploaded image bytes: {}", e.getMessage(), e);
            return ResponseEntity.badRequest().body(ApiResponse.<MealAnalysisJobDTO>builder()
                    .meta(buildMeta("validation-error"))
                    .build());
        }

        String targetDate = MealAnalysisJobService.resolveTargetDate(date);
        String userId = UserContext.getRequiredUserId();

        // ── Enqueue the job and kick off background processing ──────────
        MealAnalysisJob job = jobService.createJob(userId, validatedMealType, targetDate, description);
        jobService.process(job.getId(), userId, imageBytes, description, validatedMealType, targetDate);

        MealAnalysisJobDTO dto = MealAnalysisJobDTO.builder()
                .jobId(job.getId())
                .status(job.getStatus().name())
                .build();

        return ResponseEntity.accepted().body(ApiResponse.<MealAnalysisJobDTO>builder()
                .data(dto)
                .meta(buildMeta("ai-analyze-queued"))
                .build());
    }

    /**
     * Poll the status of a previously started analysis job.
     */
    @GetMapping("/analyze/{jobId}")
    @Operation(
            summary = "Poll AI Meal Analysis job",
            description = "Returns the job status. When COMPLETED the full analysis result is included; when FAILED an errorSource is provided.")
    public ResponseEntity<ApiResponse<MealAnalysisJobDTO>> getAnalysisJob(@PathVariable String jobId) {
        String userId = UserContext.getRequiredUserId();

        return jobService.getJob(jobId, userId)
                .map(job -> {
                    MealAnalysisJobDTO.MealAnalysisJobDTOBuilder dto = MealAnalysisJobDTO.builder()
                            .jobId(job.getId())
                            .status(job.getStatus().name());
                    if (job.getStatus() == MealAnalysisJob.Status.COMPLETED) {
                        dto.result(job.getResult());
                    } else if (job.getStatus() == MealAnalysisJob.Status.FAILED) {
                        dto.errorSource(job.getErrorSource());
                    }
                    return ResponseEntity.ok(ApiResponse.<MealAnalysisJobDTO>builder()
                            .data(dto.build())
                            .meta(buildMeta("ai-analyze-status"))
                            .build());
                })
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(ApiResponse.<MealAnalysisJobDTO>builder()
                                .meta(buildMeta("not-found"))
                                .build()));
    }

    // ─── Private Helpers ──────────────────────────────────────────────

    private String validateMealType(String mealType) {
        if (mealType == null) return null;
        String[] valid = {"Breakfast", "Lunch", "Dinner", "Snack", "Midnight", "Post Workout", "Mid-Morning"};
        for (String v : valid) {
            if (v.equalsIgnoreCase(mealType.trim())) return v;
        }
        return null;
    }

    private ApiMeta buildMeta(String action) {
        return ApiMeta.builder()
                .requestId(UUID.randomUUID().toString())
                .timestamp(Instant.now().toString())
                .source("meal-analysis-" + action)
                .build();
    }
}
