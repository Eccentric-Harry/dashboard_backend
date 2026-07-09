package com.personal_dashboard.backend.controller;

import com.personal_dashboard.backend.dto.ApiMeta;
import com.personal_dashboard.backend.dto.ApiResponse;
import com.personal_dashboard.backend.dto.TransactionDTO;
import com.personal_dashboard.backend.dto.request.TransactionRequest;
import com.personal_dashboard.backend.model.DailyFinancialLog;
import com.personal_dashboard.backend.model.SliceRepayment;
import com.personal_dashboard.backend.repository.SliceRepaymentRepository;
import com.personal_dashboard.backend.security.UserContext;
import com.personal_dashboard.backend.service.FinanceService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Thin controller for the finance route. Request/response mapping only —
 * all business logic lives in {@link FinanceService}. Money is stored as one
 * {@link DailyFinancialLog} per user per day (grouped by category); there is no
 * separate flat transactions collection.
 */
@RestController
@RequestMapping("/api/v1/finance")
@RequiredArgsConstructor
public class FinanceController {

        private final FinanceService financeService;
        private final SliceRepaymentRepository sliceRepaymentRepository;

        @GetMapping("/daily-logs")
        public ResponseEntity<ApiResponse<List<DailyFinancialLog>>> getDailyLogs(
                        @RequestParam(value = "days", defaultValue = "30") int days) {
                return ResponseEntity.ok(wrap(financeService.getDailyLogs(days)));
        }

        @PostMapping("/transactions")
        public ResponseEntity<ApiResponse<TransactionDTO>> createTransaction(
                        @Valid @RequestBody TransactionRequest request) {
                return ResponseEntity.status(HttpStatus.CREATED)
                                .body(wrap(financeService.createTransaction(request)));
        }

        @PutMapping("/transactions/{id}")
        public ResponseEntity<ApiResponse<TransactionDTO>> updateTransaction(
                        @PathVariable String id,
                        @Valid @RequestBody TransactionRequest request) {
                return ResponseEntity.ok(wrap(financeService.updateTransaction(id, request)));
        }

        @DeleteMapping("/transactions/{id}")
        public ResponseEntity<ApiResponse<Void>> deleteTransaction(@PathVariable String id) {
                financeService.deleteTransaction(id);
                return ResponseEntity.ok(wrap((Void) null));
        }

        @GetMapping("/slice-repayments")
        public ResponseEntity<ApiResponse<List<SliceRepayment>>> getSliceRepayments() {
                String userId = UserContext.getRequiredUserId();
                return ResponseEntity.ok(wrap(sliceRepaymentRepository.findByUserId(userId)));
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
