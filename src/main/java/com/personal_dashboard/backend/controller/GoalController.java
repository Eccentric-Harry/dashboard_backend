package com.personal_dashboard.backend.controller;

import com.personal_dashboard.backend.dto.ApiMeta;
import com.personal_dashboard.backend.dto.ApiResponse;
import com.personal_dashboard.backend.dto.GoalBoardResponse;
import com.personal_dashboard.backend.dto.GoalBoardResponse.GoalProgressView;
import com.personal_dashboard.backend.dto.GoalJourneyResponse;
import com.personal_dashboard.backend.dto.request.GoalCheckInRequest;
import com.personal_dashboard.backend.dto.request.GoalKitPageRequest;
import com.personal_dashboard.backend.dto.request.GoalRequest;
import com.personal_dashboard.backend.dto.request.GoalStatusRequest;
import com.personal_dashboard.backend.model.Goal;
import com.personal_dashboard.backend.model.GoalKit;
import com.personal_dashboard.backend.service.GoalKitService;
import com.personal_dashboard.backend.service.GoalService;
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
import java.util.Map;
import java.util.UUID;

/**
 * The /goals route. Every endpoint that changes progress takes an optional {@code today}
 * (the client's local day) and answers with that goal's freshly judged view.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/goals")
@RequiredArgsConstructor
@Tag(name = "Goals", description = "Personal goals, check-ins and the derived weekly board")
public class GoalController {

    private final GoalService goalService;
    private final GoalKitService goalKitService;

    @GetMapping("/board")
    @Operation(summary = "Goals board", description = "Active goals judged for today: today's progress, this week's cells and pace, recent weeks, weeks kept")
    public ResponseEntity<ApiResponse<GoalBoardResponse>> getBoard(@RequestParam(name = "today", required = false) String today) {
        return ok(goalService.getBoard(parse(today)), "board");
    }

    @GetMapping
    @Operation(summary = "List goals", description = "Active and archived goals in board order")
    public ResponseEntity<ApiResponse<List<Goal>>> listGoals() {
        return ok(goalService.listGoals(), "list");
    }

    @PostMapping
    @Operation(summary = "Create goal")
    public ResponseEntity<ApiResponse<GoalProgressView>> createGoal(
            @Valid @RequestBody GoalRequest request,
            @RequestParam(name = "today", required = false) String today) {
        return ok(goalService.createGoal(request, parse(today)), "create");
    }

    @PutMapping("/{id}")
    @Operation(summary = "Edit goal", description = "History is re-judged under the new rule")
    public ResponseEntity<ApiResponse<GoalProgressView>> updateGoal(
            @PathVariable String id,
            @Valid @RequestBody GoalRequest request,
            @RequestParam(name = "today", required = false) String today) {
        return ok(goalService.updateGoal(id, request, parse(today)), "update");
    }

    @PatchMapping("/{id}/status")
    @Operation(summary = "Archive or restore a goal")
    public ResponseEntity<ApiResponse<Goal>> setStatus(@PathVariable String id, @Valid @RequestBody GoalStatusRequest request) {
        return ok(goalService.setStatus(id, request.getStatus()), "status");
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete goal", description = "Removes the goal and every check-in it has")
    public ResponseEntity<ApiResponse<Void>> deleteGoal(@PathVariable String id) {
        goalService.deleteGoal(id);
        return ok(null, "delete");
    }

    @PostMapping("/{id}/checkins")
    @Operation(summary = "Log progress", description = "COUNT goals add the value; CHECK goals mark the day (idempotent)")
    public ResponseEntity<ApiResponse<GoalProgressView>> addCheckIn(
            @PathVariable String id,
            @Valid @RequestBody GoalCheckInRequest request,
            @RequestParam(name = "today", required = false) String today) {
        return ok(goalService.addCheckIn(id, request, parse(today)), "checkin");
    }

    @GetMapping("/{id}/journey")
    @Operation(summary = "A goal's journey", description = "Every day the goal was tended, oldest first, with the practices logged — for the goal's own world")
    public ResponseEntity<ApiResponse<GoalJourneyResponse>> getJourney(
            @PathVariable String id,
            @RequestParam(name = "today", required = false) String today) {
        return ok(goalService.getJourney(id, parse(today)), "journey");
    }

    @PutMapping("/{id}/kit/{page}")
    @Operation(summary = "Save one page of a goal world's kit", description = "Sets only that page; empty picks and fields clear it. Returns every page")
    public ResponseEntity<ApiResponse<Map<String, GoalKit.Page>>> saveKitPage(
            @PathVariable String id,
            @PathVariable String page,
            @Valid @RequestBody GoalKitPageRequest request) {
        return ok(goalKitService.savePage(id, page, request), "kit");
    }

    @DeleteMapping("/{id}/checkins/{checkInId}")
    @Operation(summary = "Undo a check-in")
    public ResponseEntity<ApiResponse<GoalProgressView>> deleteCheckIn(
            @PathVariable String id,
            @PathVariable String checkInId,
            @RequestParam(name = "today", required = false) String today) {
        return ok(goalService.deleteCheckIn(id, checkInId, parse(today)), "checkin-delete");
    }

    private static LocalDate parse(String date) {
        return date == null || date.isBlank() ? null : LocalDate.parse(date);
    }

    private static <T> ResponseEntity<ApiResponse<T>> ok(T data, String action) {
        return ResponseEntity.ok(ApiResponse.<T>builder()
                .data(data)
                .meta(ApiMeta.builder()
                        .requestId(UUID.randomUUID().toString())
                        .timestamp(Instant.now().toString())
                        .source("goals-" + action)
                        .build())
                .build());
    }
}
