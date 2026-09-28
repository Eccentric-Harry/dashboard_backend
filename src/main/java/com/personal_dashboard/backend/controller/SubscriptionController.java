package com.personal_dashboard.backend.controller;

import com.personal_dashboard.backend.dto.ApiMeta;
import com.personal_dashboard.backend.dto.ApiResponse;
import com.personal_dashboard.backend.dto.SubscriptionDTO;
import com.personal_dashboard.backend.dto.TransactionDTO;
import com.personal_dashboard.backend.dto.request.SubscriptionPaymentRequest;
import com.personal_dashboard.backend.dto.request.SubscriptionRequest;
import com.personal_dashboard.backend.service.SubscriptionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Recurring bills and subscriptions. Request/response mapping only — the schedule model and
 * the payment → ledger link live in {@link SubscriptionService}.
 */
@RestController
@RequestMapping("/api/v1/subscriptions")
@RequiredArgsConstructor
public class SubscriptionController {

    private final SubscriptionService subscriptionService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<SubscriptionDTO>>> getSubscriptions() {
        return ResponseEntity.ok(wrap(subscriptionService.list()));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<SubscriptionDTO>> createSubscription(
            @Valid @RequestBody SubscriptionRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(wrap(subscriptionService.create(request)));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<SubscriptionDTO>> updateSubscription(
            @PathVariable String id,
            @Valid @RequestBody SubscriptionRequest request) {
        return ResponseEntity.ok(wrap(subscriptionService.update(id, request)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteSubscription(@PathVariable String id) {
        subscriptionService.delete(id);
        return ResponseEntity.ok(wrap((Void) null));
    }

    /** Logs a payment for this bill as a linked Expense transaction; returns that transaction. */
    @PostMapping("/{id}/payments")
    public ResponseEntity<ApiResponse<TransactionDTO>> paySubscription(
            @PathVariable String id,
            @Valid @RequestBody(required = false) SubscriptionPaymentRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(wrap(subscriptionService.pay(id, request)));
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
