package com.personal_dashboard.backend.controller;

import com.personal_dashboard.backend.dto.ApiMeta;
import com.personal_dashboard.backend.dto.ApiResponse;
import com.personal_dashboard.backend.dto.request.PursuitRequest;
import com.personal_dashboard.backend.model.LearningPursuit;
import com.personal_dashboard.backend.service.LearningPursuitService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping({"/api/pursuits", "/api/v1/pursuits"})
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Learning Pursuits", description = "Endpoints for managing learning pursuits and their subtasks")
public class LearningPursuitController {

    private final LearningPursuitService service;

    @GetMapping
    @Operation(summary = "Get all learning pursuits", description = "Fetch all learning pursuits")
    public ResponseEntity<ApiResponse<List<LearningPursuit>>> getPursuits() {
        log.info("REST request to fetch all learning pursuits");
        List<LearningPursuit> pursuits = service.getAllPursuits();
        ApiResponse<List<LearningPursuit>> response = ApiResponse.<List<LearningPursuit>>builder()
                .data(pursuits)
                .meta(buildMeta("fetch"))
                .build();
        return ResponseEntity.ok(response);
    }

    @PostMapping
    @Operation(summary = "Create a new learning pursuit", description = "Create a pursuit with steps and register it in Notion")
    public ResponseEntity<ApiResponse<LearningPursuit>> createPursuit(
            @Valid @RequestBody PursuitRequest request) {
        log.info("REST request to create learning pursuit: {}", request.getTitle());
        LearningPursuit created = service.createPursuit(request);
        ApiResponse<LearningPursuit> response = ApiResponse.<LearningPursuit>builder()
                .data(created)
                .meta(buildMeta("create"))
                .build();
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PatchMapping("/{id}/steps/{stepId}")
    @Operation(summary = "Toggle a step status", description = "Toggle the completed state of a specific subtask step")
    public ResponseEntity<ApiResponse<LearningPursuit>> toggleStep(
            @PathVariable String id,
            @PathVariable String stepId) {
        log.info("REST request to toggle step {} in pursuit {}", stepId, id);
        LearningPursuit updated = service.toggleStep(id, stepId);
        ApiResponse<LearningPursuit> response = ApiResponse.<LearningPursuit>builder()
                .data(updated)
                .meta(buildMeta("toggle-step"))
                .build();
        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete a learning pursuit", description = "Delete a learning pursuit by id")
    public ResponseEntity<ApiResponse<Void>> deletePursuit(@PathVariable String id) {
        log.info("REST request to delete learning pursuit: {}", id);
        service.deletePursuit(id);
        ApiResponse<Void> response = ApiResponse.<Void>builder()
                .data(null)
                .meta(buildMeta("delete"))
                .build();
        return ResponseEntity.ok(response);
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update a learning pursuit metadata", description = "Update the title and category of an existing pursuit")
    public ResponseEntity<ApiResponse<LearningPursuit>> updatePursuit(
            @PathVariable String id,
            @Valid @RequestBody PursuitRequest request) {
        log.info("REST request to update learning pursuit: {}, id: {}", request.getTitle(), id);
        LearningPursuit updated = service.updatePursuit(id, request);
        ApiResponse<LearningPursuit> response = ApiResponse.<LearningPursuit>builder()
                .data(updated)
                .meta(buildMeta("update"))
                .build();
        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/{id}/steps/{stepId}")
    @Operation(summary = "Delete a subtask step", description = "Delete a subtask step from a learning pursuit")
    public ResponseEntity<ApiResponse<LearningPursuit>> deleteStep(
            @PathVariable String id,
            @PathVariable String stepId) {
        log.info("REST request to delete step {} from pursuit {}", stepId, id);
        LearningPursuit updated = service.deleteStep(id, stepId);
        ApiResponse<LearningPursuit> response = ApiResponse.<LearningPursuit>builder()
                .data(updated)
                .meta(buildMeta("delete-step"))
                .build();
        return ResponseEntity.ok(response);
    }

    @PutMapping("/{id}/steps/{stepId}")
    @Operation(summary = "Update a subtask step text", description = "Update the text of a subtask step")
    public ResponseEntity<ApiResponse<LearningPursuit>> updateStep(
            @PathVariable String id,
            @PathVariable String stepId,
            @RequestBody Map<String, String> body) {
        String newText = body.get("text");
        log.info("REST request to update step {} text to '{}' in pursuit {}", stepId, newText, id);
        LearningPursuit updated = service.updateStep(id, stepId, newText);
        ApiResponse<LearningPursuit> response = ApiResponse.<LearningPursuit>builder()
                .data(updated)
                .meta(buildMeta("update-step"))
                .build();
        return ResponseEntity.ok(response);
    }

    private ApiMeta buildMeta(String action) {
        return ApiMeta.builder()
                .requestId(UUID.randomUUID().toString())
                .timestamp(Instant.now().toString())
                .source("pursuits-" + action)
                .build();
    }
}
