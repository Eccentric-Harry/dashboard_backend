package com.personal_dashboard.backend.controller;

import com.personal_dashboard.backend.model.GoogleSyncStore;
import com.personal_dashboard.backend.repository.GoogleSyncStoreRepository;
import com.personal_dashboard.backend.service.GoogleSyncService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/webhooks")
@RequiredArgsConstructor
@Tag(name = "Google Webhook Endpoint", description = "Receiver for Google Calendar real-time change notifications")
@Slf4j
public class GoogleWebhookController {

    private final GoogleSyncStoreRepository syncStoreRepository;
    private final GoogleSyncService googleSyncService;

    @PostMapping("/google-calendar")
    @Operation(summary = "Google Calendar Webhook Receiver", description = "Fast-response endpoint to receive events watch notifications")
    public ResponseEntity<Void> receiveGoogleCalendarNotification(
            @RequestHeader("X-Goog-Channel-ID") String channelId,
            @RequestHeader("X-Goog-Resource-ID") String resourceId,
            @RequestHeader("X-Goog-Resource-State") String resourceState) {
        
        log.info("Received Google Calendar Webhook notification. ChannelID={}, ResourceID={}, State={}",
                channelId, resourceId, resourceState);

        // Immediate Acknowledgement: Google demands an instant response.
        // We will validate the signatures, spin off the work to a background thread pool, and return 200 OK.
        
        if ("sync".equalsIgnoreCase(resourceState)) {
            log.info("Google Webhook verification handshake received for channel ID: {}", channelId);
            return ResponseEntity.ok().build();
        }

        // Retrieve user credentials using channelId
        GoogleSyncStore store = syncStoreRepository.findByWebhookChannelId(channelId).orElse(null);
        if (store == null) {
            log.warn("Google Webhook warning: Channel ID not recognized in database: {}", channelId);
            return ResponseEntity.ok().build(); // Return 200 to acknowledge regardless
        }

        // Validate webhook resource ID authenticity
        if (!resourceId.equals(store.getWebhookResourceId())) {
            log.warn("Google Webhook warning: Resource ID mismatch. Expected {}, received {}",
                    store.getWebhookResourceId(), resourceId);
            return ResponseEntity.ok().build(); // Return 200 anyway
        }

        // Spin off processing of database changes to a background task
        googleSyncService.triggerSyncAsync(store.getUserId(), false);

        return ResponseEntity.ok().build();
    }
}
