package com.personal_dashboard.backend.controller;

import com.personal_dashboard.backend.dto.ApiMeta;
import com.personal_dashboard.backend.dto.ApiResponse;
import com.personal_dashboard.backend.dto.request.DailyTaskRequest;
import com.personal_dashboard.backend.model.DailyTask;
import com.personal_dashboard.backend.service.DailyTaskService;
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
@RequestMapping("/api/v1/learnings/tasks")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Learning Tasks", description = "Scheduled daily tasks for the learnings route")
public class DailyTaskController {

    private final DailyTaskService dailyTaskService;
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE;

    @GetMapping
    @Operation(summary = "Get active tasks", description = "Fetch all active tasks (incomplete or completed <= 48h ago)")
    public ResponseEntity<ApiResponse<List<DailyTask>>> getTasks(
            @RequestParam(name = "date", required = false) String dateStr) {
        List<DailyTask> tasks = dateStr != null && !dateStr.isBlank()
                ? dailyTaskService.getTasksForDateWithIncompletePrevious(LocalDate.parse(dateStr, DATE_FORMATTER))
                : dailyTaskService.getActiveTasks();
        return ResponseEntity.ok(ApiResponse.<List<DailyTask>>builder()
                .data(tasks)
                .meta(buildMeta("fetch"))
                .build());
    }

    @GetMapping("/range")
    @Operation(summary = "Get tasks for range", description = "Fetch tasks between start and end dates")
    public ResponseEntity<ApiResponse<List<DailyTask>>> getTasksRange(
            @RequestParam(name = "startDate") String startDateStr,
            @RequestParam(name = "endDate") String endDateStr) {
        LocalDate startDate = LocalDate.parse(startDateStr, DATE_FORMATTER);
        LocalDate endDate = LocalDate.parse(endDateStr, DATE_FORMATTER);
        List<DailyTask> tasks = dailyTaskService.getTasksForRange(startDate, endDate);
        return ResponseEntity.ok(ApiResponse.<List<DailyTask>>builder()
                .data(tasks)
                .meta(buildMeta("range"))
                .build());
    }

    @PostMapping
    @Operation(summary = "Create task", description = "Add a new scheduled task")
    public ResponseEntity<ApiResponse<DailyTask>> createTask(@Valid @RequestBody DailyTaskRequest request) {
        DailyTask created = dailyTaskService.createTask(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.<DailyTask>builder()
                .data(created)
                .meta(buildMeta("create"))
                .build());
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update task", description = "Update an existing task")
    public ResponseEntity<ApiResponse<DailyTask>> updateTask(
            @PathVariable String id,
            @Valid @RequestBody DailyTaskRequest request) {
        DailyTask updated = dailyTaskService.updateTask(id, request);
        return ResponseEntity.ok(ApiResponse.<DailyTask>builder()
                .data(updated)
                .meta(buildMeta("update"))
                .build());
    }

    @PatchMapping("/{id}/toggle")
    @Operation(summary = "Toggle task completion", description = "Flip completed status")
    public ResponseEntity<ApiResponse<DailyTask>> toggleTask(@PathVariable String id) {
        DailyTask updated = dailyTaskService.toggleTask(id);
        return ResponseEntity.ok(ApiResponse.<DailyTask>builder()
                .data(updated)
                .meta(buildMeta("toggle"))
                .build());
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete task", description = "Delete a task by id")
    public ResponseEntity<ApiResponse<Void>> deleteTask(@PathVariable String id) {
        dailyTaskService.deleteTask(id);
        return ResponseEntity.ok(ApiResponse.<Void>builder()
                .data(null)
                .meta(buildMeta("delete"))
                .build());
    }

    private ApiMeta buildMeta(String action) {
        return ApiMeta.builder()
                .requestId(UUID.randomUUID().toString())
                .timestamp(Instant.now().toString())
                .source("learnings-tasks-" + action)
                .build();
    }
}
