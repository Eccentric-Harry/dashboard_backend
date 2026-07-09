package com.personal_dashboard.backend.controller;

import com.personal_dashboard.backend.dto.ApiMeta;
import com.personal_dashboard.backend.dto.ApiResponse;
import com.personal_dashboard.backend.dto.SubscriptionDTO;
import com.personal_dashboard.backend.model.Subscription;
import com.personal_dashboard.backend.repository.SubscriptionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/subscriptions")
@RequiredArgsConstructor
public class SubscriptionController {

    private final SubscriptionRepository subscriptionRepository;
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE;

    @GetMapping
    public ResponseEntity<ApiResponse<List<SubscriptionDTO>>> getSubscriptions() {
        String userId = com.personal_dashboard.backend.security.UserContext.getRequiredUserId();
        List<Subscription> subscriptions = subscriptionRepository.findByUserId(userId);

        List<SubscriptionDTO> dtos = subscriptions.stream()
                .map(sub -> SubscriptionDTO.builder()
                        .id(sub.getId())
                        .name(sub.getName())
                        .cost(sub.getCost())
                        .billingDate(sub.getBillingDate() != null ? sub.getBillingDate().toString() : null)
                        .build())
                .collect(Collectors.toList());

        ApiMeta meta = ApiMeta.builder()
                .requestId(UUID.randomUUID().toString())
                .timestamp(Instant.now().toString())
                .source("api")
                .build();

        ApiResponse<List<SubscriptionDTO>> response = ApiResponse.<List<SubscriptionDTO>>builder()
                .data(dtos)
                .meta(meta)
                .build();

        return ResponseEntity.ok(response);
    }

    @PostMapping
    public ResponseEntity<ApiResponse<SubscriptionDTO>> createSubscription(@RequestBody SubscriptionDTO request) {
        Instant billingDateInstant = null;
        if (request.getBillingDate() != null && !request.getBillingDate().isBlank()) {
            LocalDate localDate = LocalDate.parse(request.getBillingDate(), DATE_FORMATTER);
            billingDateInstant = localDate.atStartOfDay()
                    .atZone(ZoneId.systemDefault())
                    .toInstant();
        }

        Subscription subscription = Subscription.builder()
                .name(request.getName())
                .cost(request.getCost())
                .billingDate(billingDateInstant)
                .build();

        Subscription saved = subscriptionRepository.save(subscription);

        SubscriptionDTO dto = SubscriptionDTO.builder()
                .id(saved.getId())
                .name(saved.getName())
                .cost(saved.getCost())
                .billingDate(saved.getBillingDate() != null ? saved.getBillingDate().toString() : null)
                .build();

        ApiMeta meta = ApiMeta.builder()
                .requestId(UUID.randomUUID().toString())
                .timestamp(Instant.now().toString())
                .source("api")
                .build();

        ApiResponse<SubscriptionDTO> response = ApiResponse.<SubscriptionDTO>builder()
                .data(dto)
                .meta(meta)
                .build();

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteSubscription(@PathVariable String id) {
        String userId = com.personal_dashboard.backend.security.UserContext.getRequiredUserId();
        Subscription subscription = subscriptionRepository.findById(id)
                .filter(sub -> userId.equals(sub.getUserId()))
                .orElseThrow(() -> new IllegalArgumentException("Subscription not found: " + id));
        subscriptionRepository.delete(subscription);

        ApiMeta meta = ApiMeta.builder()
                .requestId(UUID.randomUUID().toString())
                .timestamp(Instant.now().toString())
                .source("api")
                .build();

        ApiResponse<Void> response = ApiResponse.<Void>builder()
                .data(null)
                .meta(meta)
                .build();

        return ResponseEntity.ok(response);
    }
}
