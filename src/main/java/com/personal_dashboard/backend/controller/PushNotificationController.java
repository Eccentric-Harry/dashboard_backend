package com.personal_dashboard.backend.controller;

import com.personal_dashboard.backend.dto.ApiMeta;
import com.personal_dashboard.backend.dto.ApiResponse;
import com.personal_dashboard.backend.dto.request.PushSubscriptionRequest;
import com.personal_dashboard.backend.model.PushSubscription;
import com.personal_dashboard.backend.repository.PushSubscriptionRepository;
import com.personal_dashboard.backend.service.PushNotificationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/push")
@RequiredArgsConstructor
@CrossOrigin
@Tag(name = "Push Notifications", description = "VAPID key retrieval and browser Web Push device registrations")
public class PushNotificationController {

    private final PushNotificationService pushNotificationService;
    private final PushSubscriptionRepository pushSubscriptionRepository;

    @GetMapping("/public-key")
    @Operation(summary = "Get VAPID public key", description = "Fetch the active base64 VAPID public key to subscribe browsers")
    public ResponseEntity<ApiResponse<String>> getPublicKey() {
        return ResponseEntity.ok(ApiResponse.<String>builder()
                .data(pushNotificationService.getPublicKey())
                .meta(buildMeta("public-key"))
                .build());
    }

    @PostMapping("/subscribe")
    @Operation(summary = "Register browser push client", description = "Store a new subscription endpoint with cryptography credentials")
    public ResponseEntity<ApiResponse<PushSubscription>> subscribe(@Valid @RequestBody PushSubscriptionRequest request) {
        String userId = com.personal_dashboard.backend.security.UserContext.getRequiredUserId();
        // Prevent duplicate endpoint entries
        Optional<PushSubscription> existing = pushSubscriptionRepository.findByUserIdAndEndpoint(userId, request.getEndpoint());
        PushSubscription saved;

        if (existing.isPresent()) {
            PushSubscription entity = existing.get();
            entity.setP256dh(request.getP256dh());
            entity.setAuth(request.getAuth());
            entity.setTimezone(request.getTimezone());
            saved = pushSubscriptionRepository.save(entity);
        } else {
            PushSubscription newSub = PushSubscription.builder()
                    .userId(userId)
                    .endpoint(request.getEndpoint())
                    .p256dh(request.getP256dh())
                    .auth(request.getAuth())
                    .timezone(request.getTimezone())
                    .build();
            saved = pushSubscriptionRepository.save(newSub);
        }

        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.<PushSubscription>builder()
                .data(saved)
                .meta(buildMeta("subscribe"))
                .build());
    }

    @PostMapping("/unsubscribe")
    @Operation(summary = "Unregister browser push client", description = "Delete a subscription endpoint to disable future push alerts")
    public ResponseEntity<ApiResponse<Void>> unsubscribe(@RequestParam String endpoint) {
        String userId = com.personal_dashboard.backend.security.UserContext.getRequiredUserId();
        Optional<PushSubscription> existing = pushSubscriptionRepository.findByUserIdAndEndpoint(userId, endpoint);
        existing.ifPresent(pushSubscriptionRepository::delete);

        return ResponseEntity.ok(ApiResponse.<Void>builder()
                .data(null)
                .meta(buildMeta("unsubscribe"))
                .build());
    }

    private ApiMeta buildMeta(String action) {
        return ApiMeta.builder()
                .requestId(UUID.randomUUID().toString())
                .timestamp(Instant.now().toString())
                .source("push-notifications-" + action)
                .build();
    }
}
