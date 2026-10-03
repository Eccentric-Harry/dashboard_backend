package com.personal_dashboard.backend.controller;

import com.personal_dashboard.backend.dto.ApiMeta;
import com.personal_dashboard.backend.dto.ApiResponse;
import com.personal_dashboard.backend.dto.SavingsGoalDTO;
import com.personal_dashboard.backend.dto.request.GoalArchiveRequest;
import com.personal_dashboard.backend.dto.request.GoalMoneyRequest;
import com.personal_dashboard.backend.dto.request.GoalPurchaseRequest;
import com.personal_dashboard.backend.dto.request.SavingsGoalRequest;
import com.personal_dashboard.backend.service.SavingsGoalService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Savings goals ("Saving for" on /finance). Request/response mapping only — the plan, the
 * ledger rows that move money in and out of a goal, and the derived totals live in
 * {@link SavingsGoalService}.
 */
@RestController
@RequestMapping("/api/v1/savings-goals")
@RequiredArgsConstructor
public class SavingsGoalController {

    private final SavingsGoalService savingsGoalService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<SavingsGoalDTO>>> list(
            @RequestParam(value = "includeArchived", defaultValue = "false") boolean includeArchived) {
        return ResponseEntity.ok(wrap(savingsGoalService.list(includeArchived)));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<SavingsGoalDTO>> create(@Valid @RequestBody SavingsGoalRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(wrap(savingsGoalService.create(request)));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<SavingsGoalDTO>> update(
            @PathVariable String id, @Valid @RequestBody SavingsGoalRequest request) {
        return ResponseEntity.ok(wrap(savingsGoalService.update(id, request)));
    }

    /** Logs a Transfer OUT into the goal. */
    @PostMapping("/{id}/set-aside")
    public ResponseEntity<ApiResponse<SavingsGoalDTO>> setAside(
            @PathVariable String id, @Valid @RequestBody GoalMoneyRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(wrap(savingsGoalService.setAside(id, request)));
    }

    /** Logs a Transfer IN out of the goal (never more than it holds). */
    @PostMapping("/{id}/take-out")
    public ResponseEntity<ApiResponse<SavingsGoalDTO>> takeOut(
            @PathVariable String id, @Valid @RequestBody GoalMoneyRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(wrap(savingsGoalService.takeOut(id, request)));
    }

    /** Records the purchase the goal saved for and marks it bought. */
    @PostMapping("/{id}/buy")
    public ResponseEntity<ApiResponse<SavingsGoalDTO>> buy(
            @PathVariable String id, @Valid @RequestBody GoalPurchaseRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(wrap(savingsGoalService.buy(id, request)));
    }

    /** Soft-delete; optionally releases what's left in the goal back to the balance. */
    @PostMapping("/{id}/archive")
    public ResponseEntity<ApiResponse<SavingsGoalDTO>> archive(
            @PathVariable String id, @RequestBody(required = false) GoalArchiveRequest request) {
        return ResponseEntity.ok(wrap(savingsGoalService.archive(id, request != null && request.isRelease())));
    }

    private static <T> ApiResponse<T> wrap(T data) {
        ApiMeta meta = ApiMeta.builder()
                .requestId(UUID.randomUUID().toString())
                .timestamp(Instant.now().toString())
                .source("api")
                .build();
        return ApiResponse.<T>builder().data(data).meta(meta).build();
    }
}
