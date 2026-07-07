package com.personal_dashboard.backend.controller;

import com.personal_dashboard.backend.dto.ApiMeta;
import com.personal_dashboard.backend.dto.ApiResponse;
import com.personal_dashboard.backend.model.GoogleSyncStore;
import com.personal_dashboard.backend.repository.GoogleSyncStoreRepository;
import com.personal_dashboard.backend.security.UserContext;
import com.personal_dashboard.backend.service.GoogleCalendarClient;
import com.personal_dashboard.backend.service.GoogleSyncService;
import com.personal_dashboard.backend.util.EncryptionUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/google-calendar")
@RequiredArgsConstructor
@Tag(name = "Google Calendar Sync Auth", description = "OAuth authentication and channel management for Google Calendar")
@Slf4j
public class GoogleOAuthController {

    private final GoogleCalendarClient googleCalendarClient;
    private final GoogleSyncService googleSyncService;
    private final GoogleSyncStoreRepository syncStoreRepository;
    private final EncryptionUtils encryptionUtils;

    @Value("${google.calendar.client-id}")
    private String clientId;

    @Value("${google.calendar.redirect-uri}")
    private String redirectUri;

    @GetMapping("/auth/url")
    @Operation(summary = "Get OAuth Redirect URL", description = "Generates the Google OAuth authorization consent redirect URL")
    public ResponseEntity<ApiResponse<Map<String, String>>> getAuthUrl() {
        String userId = UserContext.getRequiredUserId();
        
        String authUrl = String.format(
                "https://accounts.google.com/o/oauth2/v2/auth?client_id=%s&redirect_uri=%s&response_type=code" +
                        "&scope=https://www.googleapis.com/auth/calendar&access_type=offline&prompt=consent&state=%s",
                clientId, redirectUri, userId
        );

        ApiMeta meta = ApiMeta.builder()
                .requestId(UUID.randomUUID().toString())
                .timestamp(Instant.now().toString())
                .source("google-calendar-auth-url")
                .build();

        return ResponseEntity.ok(ApiResponse.<Map<String, String>>builder()
                .data(Map.of("url", authUrl))
                .meta(meta)
                .build());
    }

    @GetMapping(value = "/auth/callback", produces = MediaType.TEXT_HTML_VALUE)
    @Operation(summary = "OAuth Callback Endpoint", description = "Callback landing page redirected from Google")
    public ResponseEntity<String> oAuthCallback(
            @RequestParam("code") String code,
            @RequestParam("state") String userId) {
        
        log.info("Google OAuth callback received code for user: {}", userId);
        try {
            // Exchange code for Access and Refresh tokens
            GoogleCalendarClient.OAuthTokens tokens = googleCalendarClient.exchangeCode(code);
            String email = googleCalendarClient.getUserEmail(tokens.getAccessToken());

            // Build/Update GoogleSyncStore
            GoogleSyncStore store = syncStoreRepository.findById(userId)
                    .orElse(new GoogleSyncStore());
            
            store.setUserId(userId);
            store.setEmail(email);
            store.setAccessToken(encryptionUtils.encrypt(tokens.getAccessToken()));
            if (tokens.getRefreshToken() != null) {
                store.setRefreshToken(encryptionUtils.encrypt(tokens.getRefreshToken()));
            }

            // Save credentials first to make them available for watchCalendar call
            syncStoreRepository.save(store);

            // Immediately register Google Calendar web hook watcher
            String channelId = UUID.randomUUID().toString();
            GoogleCalendarClient.WatchResponse watchResponse = googleCalendarClient.watchCalendar(userId, channelId);

            store.setWebhookChannelId(watchResponse.getChannelId());
            store.setWebhookResourceId(watchResponse.getResourceId());
            store.setWebhookExpiration(watchResponse.getExpiration());

            syncStoreRepository.save(store);

            // Trigger initial history synchronization asynchronously
            googleSyncService.triggerSyncAsync(userId, true);

            String htmlSuccess = "<html>" +
                    "<head>" +
                    "<title>Authentication Successful</title>" +
                    "<style>" +
                    "  body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif; text-align: center; padding: 50px; background-color: #1e1e1e; color: #fff; }" +
                    "  .card { background: rgba(255, 255, 255, 0.05); padding: 40px; border-radius: 12px; display: inline-block; box-shadow: 0 4px 30px rgba(0, 0, 0, 0.5); backdrop-filter: blur(10px); }" +
                    "  h1 { color: #4CAF50; margin-bottom: 10px; }" +
                    "  p { color: #ccc; margin-bottom: 30px; }" +
                    "  .btn { background: #4CAF50; color: white; border: none; padding: 12px 24px; border-radius: 6px; font-size: 16px; cursor: pointer; text-decoration: none; }" +
                    "  .btn:hover { background: #45a049; }" +
                    "</style>" +
                    "</head>" +
                    "<body>" +
                    "<div class='card'>" +
                    "  <h1>Connection Successful!</h1>" +
                    "  <p>Your Google Calendar has been securely synced with your dashboard. You can close this window now.</p>" +
                    "  <button class='btn' onclick='window.close()'>Close Window</button>" +
                    "</div>" +
                    "<script>" +
                    "  if (window.opener) {" +
                    "    window.opener.postMessage({ type: 'GOOGLE_CALENDAR_CONNECTED', status: 'success' }, '*');" +
                    "  }" +
                    "  setTimeout(function() { window.close(); }, 3000);" +
                    "</script>" +
                    "</body>" +
                    "</html>";

            return ResponseEntity.ok(htmlSuccess);

        } catch (Exception e) {
            log.error("Google OAuth callback exchange failed for user {}", userId, e);
            String htmlFailure = "<html>" +
                    "<head>" +
                    "<title>Authentication Failed</title>" +
                    "<style>" +
                    "  body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif; text-align: center; padding: 50px; background-color: #1e1e1e; color: #fff; }" +
                    "  .card { background: rgba(255, 255, 255, 0.05); padding: 40px; border-radius: 12px; display: inline-block; box-shadow: 0 4px 30px rgba(0, 0, 0, 0.5); backdrop-filter: blur(10px); }" +
                    "  h1 { color: #f44336; margin-bottom: 10px; }" +
                    "  p { color: #ccc; margin-bottom: 30px; }" +
                    "  .btn { background: #f44336; color: white; border: none; padding: 12px 24px; border-radius: 6px; font-size: 16px; cursor: pointer; text-decoration: none; }" +
                    "</style>" +
                    "</head>" +
                    "<body>" +
                    "<div class='card'>" +
                    "  <h1>Connection Failed</h1>" +
                    "  <p>An error occurred while linking your Google account. Please try again.</p>" +
                    "  <button class='btn' onclick='window.close()'>Close Window</button>" +
                    "</div>" +
                    "</body>" +
                    "</html>";
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(htmlFailure);
        }
    }

    @GetMapping("/auth/status")
    @Operation(summary = "Get Connection Status", description = "Checks whether the user's account is connected to Google Calendar")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getStatus() {
        String userId = UserContext.getRequiredUserId();
        Optional<GoogleSyncStore> storeOpt = syncStoreRepository.findById(userId);

        ApiMeta meta = ApiMeta.builder()
                .requestId(UUID.randomUUID().toString())
                .timestamp(Instant.now().toString())
                .source("google-calendar-auth-status")
                .build();

        if (storeOpt.isEmpty()) {
            return ResponseEntity.ok(ApiResponse.<Map<String, Object>>builder()
                    .data(Map.of("connected", false))
                    .meta(meta)
                    .build());
        }

        GoogleSyncStore store = storeOpt.get();
        Map<String, Object> data = Map.of(
                "connected", true,
                "email", store.getEmail() != null ? store.getEmail() : "Google Calendar Account",
                "lastSyncedAt", store.getLastSyncedAt() != null ? store.getLastSyncedAt().toString() : "",
                "webhookExpiration", store.getWebhookExpiration() != null ? store.getWebhookExpiration().toString() : ""
        );

        return ResponseEntity.ok(ApiResponse.<Map<String, Object>>builder()
                .data(data)
                .meta(meta)
                .build());
    }

    @PostMapping("/auth/disconnect")
    @Operation(summary = "Disconnect Google Calendar", description = "Removes Google Calendar credentials and stops webhook watcher")
    public ResponseEntity<ApiResponse<Map<String, Object>>> disconnect() {
        String userId = UserContext.getRequiredUserId();
        GoogleSyncStore store = syncStoreRepository.findById(userId).orElse(null);

        if (store != null) {
            if (store.getWebhookChannelId() != null && store.getWebhookResourceId() != null) {
                // Stop watching channel asynchronously
                final String channelId = store.getWebhookChannelId();
                final String resourceId = store.getWebhookResourceId();
                new Thread(() -> googleCalendarClient.stopChannel(userId, channelId, resourceId)).start();
            }
            syncStoreRepository.deleteById(userId);
            log.info("Disconnected Google Calendar for user: {}", userId);
        }

        ApiMeta meta = ApiMeta.builder()
                .requestId(UUID.randomUUID().toString())
                .timestamp(Instant.now().toString())
                .source("google-calendar-disconnect")
                .build();

        return ResponseEntity.ok(ApiResponse.<Map<String, Object>>builder()
                .data(Map.of("status", "disconnected"))
                .meta(meta)
                .build());
    }

    @PostMapping("/sync")
    @Operation(summary = "Manually Trigger Sync", description = "Manually request an incremental sync sequence")
    public ResponseEntity<ApiResponse<Map<String, Object>>> triggerManualSync() {
        String userId = UserContext.getRequiredUserId();
        googleSyncService.triggerSyncAsync(userId, false);

        ApiMeta meta = ApiMeta.builder()
                .requestId(UUID.randomUUID().toString())
                .timestamp(Instant.now().toString())
                .source("google-calendar-sync")
                .build();

        return ResponseEntity.ok(ApiResponse.<Map<String, Object>>builder()
                .data(Map.of("status", "sync_scheduled"))
                .meta(meta)
                .build());
    }

    @PostMapping("/push-local")
    @Operation(summary = "Push Local Events to Google Calendar", description = "Finds all local tasks without a Google Event ID and pushes them to Google Calendar")
    public ResponseEntity<ApiResponse<Map<String, Object>>> pushLocalEvents() {
        String userId = UserContext.getRequiredUserId();

        if (!syncStoreRepository.existsById(userId)) {
            return ResponseEntity.status(HttpStatus.PRECONDITION_FAILED)
                    .body(ApiResponse.<Map<String, Object>>builder()
                            .data(Map.of("error", "Google Calendar not connected"))
                            .meta(ApiMeta.builder()
                                    .requestId(UUID.randomUUID().toString())
                                    .timestamp(Instant.now().toString())
                                    .source("google-calendar-push-local")
                                    .build())
                            .build());
        }

        try {
            int pushed = googleSyncService.pushLocalEventsToGoogle(userId);

            ApiMeta meta = ApiMeta.builder()
                    .requestId(UUID.randomUUID().toString())
                    .timestamp(Instant.now().toString())
                    .source("google-calendar-push-local")
                    .build();

            return ResponseEntity.ok(ApiResponse.<Map<String, Object>>builder()
                    .data(Map.of("status", "ok", "pushed", pushed))
                    .meta(meta)
                    .build());
        } catch (Exception e) {
            log.error("Failed to push local events to Google Calendar for user {}: {}", userId, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiResponse.<Map<String, Object>>builder()
                            .data(Map.of("error", "Push failed: " + e.getMessage()))
                            .meta(ApiMeta.builder()
                                    .requestId(UUID.randomUUID().toString())
                                    .timestamp(Instant.now().toString())
                                    .source("google-calendar-push-local")
                                    .build())
                            .build());
        }
    }
}
