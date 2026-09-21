package com.personal_dashboard.backend.controller;

import com.personal_dashboard.backend.dto.ApiMeta;
import com.personal_dashboard.backend.dto.ApiResponse;
import com.personal_dashboard.backend.dto.NotificationView;
import com.personal_dashboard.backend.dto.PushSubscriptionStatus;
import com.personal_dashboard.backend.dto.PushTestResult;
import com.personal_dashboard.backend.dto.request.NotificationActionRequest;
import com.personal_dashboard.backend.dto.request.PushRotateRequest;
import com.personal_dashboard.backend.dto.request.PushSubscriptionRequest;
import com.personal_dashboard.backend.model.PushSubscription;
import com.personal_dashboard.backend.service.PushNotificationService;
import com.personal_dashboard.backend.service.notification.NotificationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/v1/push")
@RequiredArgsConstructor
@CrossOrigin
@Tag(name = "Push Notifications", description = "VAPID key retrieval and browser Web Push device registrations")
public class PushNotificationController {

    private final PushNotificationService pushNotificationService;
    private final NotificationService notificationService;

    @GetMapping("/public-key")
    @Operation(summary = "Get VAPID public key", description = "Fetch the active base64 VAPID public key to subscribe browsers")
    public ResponseEntity<ApiResponse<String>> getPublicKey() {
        return ResponseEntity.ok(ApiResponse.<String>builder()
                .data(pushNotificationService.getPublicKey())
                .meta(buildMeta("public-key"))
                .build());
    }

    @PostMapping("/subscribe")
    @Operation(summary = "Register browser push client",
            description = "Idempotent: re-registering the same endpoint updates one record rather than adding another")
    public ResponseEntity<ApiResponse<PushSubscription>> subscribe(
            @Valid @RequestBody PushSubscriptionRequest request,
            @RequestHeader(value = "User-Agent", required = false) String userAgent) {
        PushSubscription saved = notificationService.register(request, userAgent);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.<PushSubscription>builder()
                .data(saved)
                .meta(buildMeta("subscribe"))
                .build());
    }

    @PostMapping("/unsubscribe")
    @Operation(summary = "Unregister browser push client",
            description = "Deletes the registration and cancels pending notifications when it was the user's last device")
    public ResponseEntity<ApiResponse<Map<String, Boolean>>> unsubscribe(@RequestParam String endpoint) {
        boolean removed = notificationService.unregister(endpoint);
        return ResponseEntity.ok(ApiResponse.<Map<String, Boolean>>builder()
                .data(Map.of("removed", removed))
                .meta(buildMeta("unsubscribe"))
                .build());
    }

    @GetMapping("/status")
    @Operation(summary = "Check this device's registration",
            description = "Lets the client reconcile its toggle with the server instead of trusting localStorage")
    public ResponseEntity<ApiResponse<PushSubscriptionStatus>> status(
            @RequestParam(required = false) String endpoint) {
        return ResponseEntity.ok(ApiResponse.<PushSubscriptionStatus>builder()
                .data(notificationService.status(endpoint))
                .meta(buildMeta("status"))
                .build());
    }

    /**
     * Called by the service worker on {@code pushsubscriptionchange}. Unauthenticated by
     * necessity — a service worker has no access to the user's token — and authorised
     * instead by knowledge of the old endpoint, which is itself a secret capability URL.
     */
    @PostMapping("/rotate")
    @Operation(summary = "Swap a rotated push endpoint",
            description = "Service-worker callback when the browser replaces a subscription")
    public ResponseEntity<ApiResponse<Map<String, Boolean>>> rotate(@Valid @RequestBody PushRotateRequest request) {
        boolean rotated = notificationService.rotate(request);
        return ResponseEntity.ok(ApiResponse.<Map<String, Boolean>>builder()
                .data(Map.of("rotated", rotated))
                .meta(buildMeta("rotate"))
                .build());
    }

    /**
     * Snooze straight from the OS notification, with the app closed. Authorised by the
     * one-shot token that travelled inside the encrypted push payload.
     */
    @PostMapping("/actions/snooze")
    @Operation(summary = "Snooze from a notification action",
            description = "Re-schedules the alert; authorised by the push payload's action token")
    public ResponseEntity<ApiResponse<NotificationView>> snooze(@Valid @RequestBody NotificationActionRequest request) {
        return notificationService.snoozeByToken(request.getActionToken(), request.getMinutes())
                .map(view -> ResponseEntity.ok(ApiResponse.<NotificationView>builder()
                        .data(view)
                        .meta(buildMeta("snooze"))
                        .build()))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(ApiResponse.<NotificationView>builder()
                                .data(null)
                                .meta(buildMeta("snooze-unknown-token"))
                                .build()));
    }

    @PostMapping("/test")
    @Operation(summary = "Send a test push now",
            description = "Bypasses the scheduler and reports what each push service said — proves the transport")
    public ResponseEntity<ApiResponse<PushTestResult>> test() {
        return ResponseEntity.ok(ApiResponse.<PushTestResult>builder()
                .data(notificationService.sendTestPush())
                .meta(buildMeta("test"))
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
