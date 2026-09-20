package com.personal_dashboard.backend.controller;

import com.personal_dashboard.backend.dto.ApiMeta;
import com.personal_dashboard.backend.dto.ApiResponse;
import com.personal_dashboard.backend.dto.NotificationView;
import com.personal_dashboard.backend.service.notification.NotificationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The notification centre. Every endpoint here is a read or a user-initiated state change;
 * none of them can cause a notification to be delivered, which is what lets the client
 * call the feed freely on boot, on focus, and on reconnect.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/notifications")
@RequiredArgsConstructor
@CrossOrigin
@Tag(name = "Notifications", description = "Server-owned notification history and read state")
public class NotificationController {

    private final NotificationService notificationService;

    @GetMapping
    @Operation(summary = "Recent notifications", description = "Delivered and failed notifications, newest first")
    public ResponseEntity<ApiResponse<List<NotificationView>>> list(
            @RequestParam(defaultValue = "50") int limit) {
        return ResponseEntity.ok(ApiResponse.<List<NotificationView>>builder()
                .data(notificationService.getFeed(limit))
                .meta(buildMeta("list"))
                .build());
    }

    @PostMapping("/{id}/ack")
    @Operation(summary = "Acknowledge delivery", description = "A client confirms it actually displayed the notification")
    public ResponseEntity<ApiResponse<NotificationView>> acknowledge(@PathVariable String id) {
        return ResponseEntity.ok(ApiResponse.<NotificationView>builder()
                .data(notificationService.acknowledge(id))
                .meta(buildMeta("ack"))
                .build());
    }

    @PostMapping("/{id}/read")
    @Operation(summary = "Mark one notification read")
    public ResponseEntity<ApiResponse<NotificationView>> markRead(@PathVariable String id) {
        return ResponseEntity.ok(ApiResponse.<NotificationView>builder()
                .data(notificationService.markRead(id))
                .meta(buildMeta("read"))
                .build());
    }

    @PostMapping("/read-all")
    @Operation(summary = "Mark every notification read")
    public ResponseEntity<ApiResponse<Map<String, Integer>>> markAllRead() {
        return ResponseEntity.ok(ApiResponse.<Map<String, Integer>>builder()
                .data(Map.of("updated", notificationService.markAllRead()))
                .meta(buildMeta("read-all"))
                .build());
    }

    @PostMapping("/{id}/dismiss")
    @Operation(summary = "Remove one notification from the feed")
    public ResponseEntity<ApiResponse<Void>> dismiss(@PathVariable String id) {
        notificationService.dismiss(id);
        return ResponseEntity.ok(ApiResponse.<Void>builder()
                .data(null)
                .meta(buildMeta("dismiss"))
                .build());
    }

    @PostMapping("/dismiss-all")
    @Operation(summary = "Clear the feed")
    public ResponseEntity<ApiResponse<Map<String, Integer>>> dismissAll() {
        return ResponseEntity.ok(ApiResponse.<Map<String, Integer>>builder()
                .data(Map.of("dismissed", notificationService.dismissAll()))
                .meta(buildMeta("dismiss-all"))
                .build());
    }

    private ApiMeta buildMeta(String action) {
        return ApiMeta.builder()
                .requestId(UUID.randomUUID().toString())
                .timestamp(Instant.now().toString())
                .source("notifications-" + action)
                .build();
    }
}
