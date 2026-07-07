package com.personal_dashboard.backend.controller;

import com.personal_dashboard.backend.dto.ApiMeta;
import com.personal_dashboard.backend.dto.ApiResponse;
import com.personal_dashboard.backend.model.CalendarSyncMapping;
import com.personal_dashboard.backend.model.GoogleSyncStore;
import com.personal_dashboard.backend.repository.CalendarSyncMappingRepository;
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
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/google-calendar")
@RequiredArgsConstructor
@Tag(name = "Google Calendar Sync", description = "Multi-account OAuth and sync management for Google Calendar")
@Slf4j
public class GoogleOAuthController {

    private final GoogleCalendarClient googleCalendarClient;
    private final GoogleSyncService googleSyncService;
    private final GoogleSyncStoreRepository syncStoreRepository;
    private final CalendarSyncMappingRepository mappingRepository;
    private final EncryptionUtils encryptionUtils;

    @Value("${google.calendar.client-id}")
    private String clientId;

    @Value("${google.calendar.redirect-uri}")
    private String redirectUri;

    // ─── OAuth flow ──────────────────────────────────────────────────────────────

    @GetMapping("/auth/url")
    @Operation(summary = "Get OAuth URL", description = "Returns the Google OAuth consent-screen URL to connect a new account")
    public ResponseEntity<ApiResponse<Map<String, String>>> getAuthUrl() {
        String userId = UserContext.getRequiredUserId();

        String authUrl = String.format(
                "https://accounts.google.com/o/oauth2/v2/auth?client_id=%s&redirect_uri=%s&response_type=code"
                        + "&scope=https://www.googleapis.com/auth/calendar"
                        + "&access_type=offline&prompt=consent&state=%s",
                clientId, redirectUri, userId
        );

        return ok("google-calendar-auth-url", Map.of("url", authUrl));
    }

    @GetMapping(value = "/auth/callback", produces = MediaType.TEXT_HTML_VALUE)
    @Operation(summary = "OAuth Callback", description = "Google redirects here after the user grants consent")
    public ResponseEntity<String> oAuthCallback(
            @RequestParam("code") String code,
            @RequestParam("state") String userId) {

        log.info("Google OAuth callback received for user: {}", userId);
        try {
            GoogleCalendarClient.OAuthTokens tokens = googleCalendarClient.exchangeCode(code);
            String email = googleCalendarClient.getUserEmail(tokens.getAccessToken());
            String storeId = GoogleSyncStore.storeId(userId, email);

            GoogleSyncStore store = syncStoreRepository.findById(storeId).orElse(new GoogleSyncStore());
            store.setId(storeId);
            store.setUserId(userId);
            store.setEmail(email);
            store.setAccessToken(encryptionUtils.encrypt(tokens.getAccessToken()));
            if (tokens.getRefreshToken() != null) {
                store.setRefreshToken(encryptionUtils.encrypt(tokens.getRefreshToken()));
            }
            // Reset sync token so a full re-sync runs for this account
            store.setCurrentSyncToken(null);
            // (Re)connecting clears any prior disconnected state.
            store.setStatus("CONNECTED");
            store.setAuthError(null);
            store.setDisconnectedAt(null);
            syncStoreRepository.save(store);

            // Register webhook watch for this account
            String channelId = UUID.randomUUID().toString();
            GoogleCalendarClient.WatchResponse watchResponse = googleCalendarClient.watchCalendar(storeId, channelId);
            store.setWebhookChannelId(watchResponse.getChannelId());
            store.setWebhookResourceId(watchResponse.getResourceId());
            store.setWebhookExpiration(watchResponse.getExpiration());
            syncStoreRepository.save(store);

            // Trigger full pull-sync asynchronously
            googleSyncService.triggerSyncAsync(userId, email, true);

            return ResponseEntity.ok(successHtml(email));

        } catch (Exception e) {
            log.error("Google OAuth callback failed for user {}", userId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorHtml());
        }
    }

    // ─── Status ──────────────────────────────────────────────────────────────────

    @GetMapping("/auth/status")
    @Operation(summary = "Get Connected Accounts", description = "Returns all Google Calendar accounts connected by this user")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getStatus() {
        String userId = UserContext.getRequiredUserId();
        List<GoogleSyncStore> stores = syncStoreRepository.findByUserId(userId);

        List<Map<String, Object>> accounts = stores.stream().map(s -> {
            Map<String, Object> a = new HashMap<>();
            a.put("email", s.getEmail());
            a.put("lastSyncedAt", s.getLastSyncedAt() != null ? s.getLastSyncedAt().toString() : "");
            a.put("webhookExpiration", s.getWebhookExpiration() != null ? s.getWebhookExpiration().toString() : "");
            a.put("status", s.getStatus() != null ? s.getStatus() : "CONNECTED");
            a.put("authError", s.getAuthError() != null ? s.getAuthError() : "");
            return a;
        }).toList();

        Map<String, Object> data = new HashMap<>();
        data.put("connected", !accounts.isEmpty());
        data.put("accounts", accounts);
        // Convenience for legacy callers that expected a single "email" field
        data.put("email", accounts.isEmpty() ? "" : accounts.get(0).get("email"));

        return ok("google-calendar-auth-status", data);
    }

    // ─── Disconnect ───────────────────────────────────────────────────────────────

    @PostMapping("/auth/disconnect")
    @Operation(summary = "Disconnect a Google Account",
               description = "Pass ?email=... to disconnect a specific account; omit to disconnect all")
    public ResponseEntity<ApiResponse<Map<String, Object>>> disconnect(
            @RequestParam(value = "email", required = false) String email) {

        String userId = UserContext.getRequiredUserId();

        if (email != null && !email.isBlank()) {
            disconnectOne(userId, email.trim());
            return ok("google-calendar-disconnect", Map.of("status", "disconnected", "email", email.trim()));
        } else {
            List<GoogleSyncStore> stores = syncStoreRepository.findByUserId(userId);
            for (GoogleSyncStore store : stores) {
                disconnectOne(userId, store.getEmail());
            }
            return ok("google-calendar-disconnect", Map.of("status", "disconnected_all"));
        }
    }

    private void disconnectOne(String userId, String email) {
        String storeId = GoogleSyncStore.storeId(userId, email);
        GoogleSyncStore store = syncStoreRepository.findById(storeId).orElse(null);
        if (store != null) {
            if (store.getWebhookChannelId() != null && store.getWebhookResourceId() != null) {
                new Thread(() -> googleCalendarClient.stopChannel(storeId, store.getWebhookChannelId(), store.getWebhookResourceId())).start();
            }
            syncStoreRepository.deleteById(storeId);
            // Mappings are intentionally kept: if user reconnects this same account,
            // events are matched without creating duplicates.
            log.info("Disconnected Google Calendar account {} for user {}", email, userId);
        }
    }

    // ─── Pull sync ────────────────────────────────────────────────────────────────

    @PostMapping("/sync")
    @Operation(summary = "Trigger Pull Sync",
               description = "Pull latest events from Google Calendar. Pass ?email=... for a specific account, omit for all.")
    public ResponseEntity<ApiResponse<Map<String, Object>>> triggerManualSync(
            @RequestParam(value = "email", required = false) String email) {

        String userId = UserContext.getRequiredUserId();

        if (email != null && !email.isBlank()) {
            googleSyncService.triggerSyncAsync(userId, email.trim(), false);
            return ok("google-calendar-sync", Map.of("status", "sync_scheduled", "email", email.trim()));
        } else {
            googleSyncService.triggerSyncAsync(userId, false);
            return ok("google-calendar-sync", Map.of("status", "sync_scheduled_all"));
        }
    }

    // ─── Push local ───────────────────────────────────────────────────────────────

    @PostMapping("/push-local")
    @Operation(summary = "Push Local Events to Google Calendar",
               description = "Pushes tasks without a mapping for the given account. "
                           + "Pass ?email=... for a specific account, omit to push to all connected accounts.")
    public ResponseEntity<ApiResponse<Map<String, Object>>> pushLocalEvents(
            @RequestParam(value = "email", required = false) String email) {

        String userId = UserContext.getRequiredUserId();
        List<GoogleSyncStore> stores = syncStoreRepository.findByUserId(userId);

        if (stores.isEmpty()) {
            return ResponseEntity.status(HttpStatus.PRECONDITION_FAILED)
                    .body(errorResponse("push-local", "No Google Calendar accounts connected"));
        }

        List<GoogleSyncStore> targets = (email != null && !email.isBlank())
                ? stores.stream().filter(s -> s.getEmail().equalsIgnoreCase(email.trim())).toList()
                : stores;

        if (targets.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(errorResponse("push-local", "Account not found: " + email));
        }

        Map<String, Object> results = new HashMap<>();
        int totalPushed = 0;
        for (GoogleSyncStore store : targets) {
            try {
                int pushed = googleSyncService.pushLocalEventsToGoogle(userId, store.getEmail());
                results.put(store.getEmail(), pushed);
                totalPushed += pushed;
            } catch (Exception e) {
                log.error("Push-local failed for account {}: {}", store.getEmail(), e.getMessage(), e);
                results.put(store.getEmail(), "error: " + e.getMessage());
            }
        }

        Map<String, Object> data = new HashMap<>();
        data.put("status", "ok");
        data.put("totalPushed", totalPushed);
        data.put("byAccount", results);
        return ok("google-calendar-push-local", data);
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────────

    private <T> ResponseEntity<ApiResponse<T>> ok(String source, T data) {
        return ResponseEntity.ok(ApiResponse.<T>builder()
                .data(data)
                .meta(ApiMeta.builder()
                        .requestId(UUID.randomUUID().toString())
                        .timestamp(Instant.now().toString())
                        .source(source)
                        .build())
                .build());
    }

    private ApiResponse<Map<String, Object>> errorResponse(String source, String message) {
        return ApiResponse.<Map<String, Object>>builder()
                .data(Map.of("error", message))
                .meta(ApiMeta.builder()
                        .requestId(UUID.randomUUID().toString())
                        .timestamp(Instant.now().toString())
                        .source(source)
                        .build())
                .build();
    }

    private String successHtml(String email) {
        return "<html><head><title>Connected</title><style>"
                + "body{font-family:-apple-system,sans-serif;text-align:center;padding:50px;background:#1e1e1e;color:#fff}"
                + ".card{background:rgba(255,255,255,.05);padding:40px;border-radius:12px;display:inline-block}"
                + "h1{color:#4CAF50}p{color:#ccc;margin-bottom:30px}"
                + ".btn{background:#4CAF50;color:#fff;border:none;padding:12px 24px;border-radius:6px;font-size:16px;cursor:pointer}"
                + "</style></head><body><div class='card'>"
                + "<h1>Connected!</h1>"
                + "<p>" + email + " is now synced with your dashboard.</p>"
                + "<button class='btn' onclick='window.close()'>Close Window</button>"
                + "</div><script>"
                + "if(window.opener){window.opener.postMessage({type:'GOOGLE_CALENDAR_CONNECTED',status:'success',email:'" + email + "'},'*');}"
                + "setTimeout(function(){window.close();},3000);"
                + "</script></body></html>";
    }

    private String errorHtml() {
        return "<html><head><title>Failed</title><style>"
                + "body{font-family:-apple-system,sans-serif;text-align:center;padding:50px;background:#1e1e1e;color:#fff}"
                + ".card{background:rgba(255,255,255,.05);padding:40px;border-radius:12px;display:inline-block}"
                + "h1{color:#f44336}p{color:#ccc;margin-bottom:30px}"
                + ".btn{background:#f44336;color:#fff;border:none;padding:12px 24px;border-radius:6px;cursor:pointer}"
                + "</style></head><body><div class='card'>"
                + "<h1>Connection Failed</h1><p>An error occurred. Please try again.</p>"
                + "<button class='btn' onclick='window.close()'>Close</button>"
                + "</div></body></html>";
    }
}
