package com.personal_dashboard.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.personal_dashboard.backend.model.DailyTask;
import com.personal_dashboard.backend.model.GoogleSyncStore;
import com.personal_dashboard.backend.model.UserAccount;
import com.personal_dashboard.backend.repository.GoogleSyncStoreRepository;
import com.personal_dashboard.backend.repository.UserAccountRepository;
import com.personal_dashboard.backend.util.EncryptionUtils;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class GoogleCalendarClient {

    private final GoogleSyncStoreRepository syncStoreRepository;
    private final UserAccountRepository userAccountRepository;
    private final EncryptionUtils encryptionUtils;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newHttpClient();

    @Value("${google.calendar.client-id}")
    private String clientId;

    @Value("${google.calendar.client-secret}")
    private String clientSecret;

    @Value("${google.calendar.redirect-uri}")
    private String redirectUri;

    @Value("${google.calendar.webhook-url}")
    private String webhookUrl;

    // Helper class to store token details
    @Getter
    public static class OAuthTokens {
        private final String accessToken;
        private final String refreshToken;
        private final Long expiresIn;

        public OAuthTokens(String accessToken, String refreshToken, Long expiresIn) {
            this.accessToken = accessToken;
            this.refreshToken = refreshToken;
            this.expiresIn = expiresIn;
        }
    }

    // Helper class to wrap events list sync results
    @Getter
    public static class SyncEventsResponse {
        private final List<JsonNode> items;
        private final String nextSyncToken;
        private final String nextPageToken;
        private final boolean gone;

        public SyncEventsResponse(List<JsonNode> items, String nextSyncToken, String nextPageToken, boolean gone) {
            this.items = items;
            this.nextSyncToken = nextSyncToken;
            this.nextPageToken = nextPageToken;
            this.gone = gone;
        }
    }

    // Helper class to wrap watch subscription response
    @Getter
    public static class WatchResponse {
        private final String channelId;
        private final String resourceId;
        private final Instant expiration;

        public WatchResponse(String channelId, String resourceId, Instant expiration) {
            this.channelId = channelId;
            this.resourceId = resourceId;
            this.expiration = expiration;
        }
    }

    /**
     * Exchange Auth code for tokens
     */
    public OAuthTokens exchangeCode(String code) throws Exception {
        String requestBody = String.format(
                "code=%s&client_id=%s&client_secret=%s&redirect_uri=%s&grant_type=authorization_code",
                code, clientId, clientSecret, redirectUri
        );

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://oauth2.googleapis.com/token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            log.error("Failed to exchange authorization code: {}", response.body());
            throw new RuntimeException("Google OAuth token exchange failed with status: " + response.statusCode());
        }

        JsonNode jsonNode = objectMapper.readTree(response.body());
        String accessToken = jsonNode.get("access_token").asText();
        String refreshToken = jsonNode.has("refresh_token") ? jsonNode.get("refresh_token").asText() : null;
        Long expiresIn = jsonNode.has("expires_in") ? jsonNode.get("expires_in").asLong() : 3600L;

        return new OAuthTokens(accessToken, refreshToken, expiresIn);
    }

    /**
     * Force refresh the token for a user
     */
    public synchronized String refreshAccessToken(String userId, String encryptedRefreshToken) {
        log.info("Refreshing access token for user: {}", userId);
        String decryptedRefreshToken = encryptionUtils.decrypt(encryptedRefreshToken);
        if (decryptedRefreshToken == null || decryptedRefreshToken.isBlank()) {
            throw new IllegalStateException("Cannot refresh access token, refresh token is missing");
        }

        try {
            String requestBody = String.format(
                    "client_id=%s&client_secret=%s&refresh_token=%s&grant_type=refresh_token",
                    clientId, clientSecret, decryptedRefreshToken
            );

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://oauth2.googleapis.com/token"))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.error("Failed to refresh access token for user {}: {}", userId, response.body());
                // If token is invalid or revoked, disconnect user
                if (response.statusCode() == 400 || response.statusCode() == 401) {
                    syncStoreRepository.deleteById(userId);
                }
                throw new RuntimeException("Google token refresh failed: " + response.statusCode());
            }

            JsonNode jsonNode = objectMapper.readTree(response.body());
            String newAccessToken = jsonNode.get("access_token").asText();
            String encryptedAccessToken = encryptionUtils.encrypt(newAccessToken);

            GoogleSyncStore store = syncStoreRepository.findById(userId)
                    .orElseThrow(() -> new IllegalArgumentException("Sync store not found for user: " + userId));
            store.setAccessToken(encryptedAccessToken);
            
            // Google occasionally issues a new refresh token
            if (jsonNode.has("refresh_token")) {
                store.setRefreshToken(encryptionUtils.encrypt(jsonNode.get("refresh_token").asText()));
            }

            syncStoreRepository.save(store);
            log.info("Successfully refreshed access token for user: {}", userId);
            return newAccessToken;

        } catch (Exception e) {
            log.error("Error occurred while refreshing token for user {}", userId, e);
            throw new RuntimeException("Failed to refresh token", e);
        }
    }

    /**
     * Retrieve a valid access token, refreshing it if necessary
     */
    public String getValidAccessToken(String userId) {
        GoogleSyncStore store = syncStoreRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("Google synchronization credentials not found for user: " + userId));

        // If the access token is near expiry or we want to guarantee validity, we can execute the API call first
        // and catch 401 or proactively refresh. Proactive refresh is cleaner.
        // For simplicity, we decrypt the current access token and let the HTTP helper retry on 401.
        return encryptionUtils.decrypt(store.getAccessToken());
    }

    /**
     * Helper to perform HTTP request with automatic token refresh
     */
    private HttpResponse<String> executeRequestWithAuth(String userId, HttpRequest.Builder requestBuilder) throws Exception {
        String accessToken = getValidAccessToken(userId);
        HttpRequest request = requestBuilder.copy()
                .header("Authorization", "Bearer " + accessToken)
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 401) {
            log.warn("Access token expired for user {}. Refreshing and retrying request.", userId);
            GoogleSyncStore store = syncStoreRepository.findById(userId)
                    .orElseThrow(() -> new IllegalArgumentException("Google sync store not found"));
            
            String newAccessToken = refreshAccessToken(userId, store.getRefreshToken());
            
            // Retry request with new token
            HttpRequest retryRequest = requestBuilder.copy()
                    .header("Authorization", "Bearer " + newAccessToken)
                    .build();
            return httpClient.send(retryRequest, HttpResponse.BodyHandlers.ofString());
        }

        return response;
    }

    /**
     * Get user profile email from Google by retrieving primary calendar details
     */
    public String getUserEmail(String accessToken) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://www.googleapis.com/calendar/v3/calendars/primary"))
                .header("Authorization", "Bearer " + accessToken)
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new RuntimeException("Failed to get primary calendar details from Google: " + response.body());
        }
        JsonNode jsonNode = objectMapper.readTree(response.body());
        if (jsonNode.has("id")) {
            return jsonNode.get("id").asText();
        }
        if (jsonNode.has("summary")) {
            return jsonNode.get("summary").asText();
        }
        return "Google Calendar Account";
    }

    /**
     * Insert Event into Google Calendar
     */
    public String insertEvent(String userId, DailyTask task) throws Exception {
        String eventJson = mapLocalToGoogleEventJson(userId, task);
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create("https://www.googleapis.com/calendar/v3/calendars/primary/events"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(eventJson));

        HttpResponse<String> response = executeRequestWithAuth(userId, requestBuilder);
        if (response.statusCode() != 200 && response.statusCode() != 201) {
            log.error("Google Event insert failed: status={}, body={}", response.statusCode(), response.body());
            throw new RuntimeException("Failed to insert event to Google Calendar: " + response.body());
        }

        JsonNode responseNode = objectMapper.readTree(response.body());
        return responseNode.get("id").asText();
    }

    /**
     * Update Event in Google Calendar
     */
    public void updateEvent(String userId, DailyTask task) throws Exception {
        if (task.getGoogleEventId() == null || task.getGoogleEventId().isBlank()) {
            throw new IllegalArgumentException("Task has no Google Event ID for update");
        }

        String eventJson = mapLocalToGoogleEventJson(userId, task);
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create("https://www.googleapis.com/calendar/v3/calendars/primary/events/" + task.getGoogleEventId()))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(eventJson));

        HttpResponse<String> response = executeRequestWithAuth(userId, requestBuilder);
        if (response.statusCode() == 404) {
            // Google event might have been deleted directly in calendar. Re-insert it.
            log.warn("Google Event not found during update (404). Re-inserting event.");
            String newGoogleId = insertEvent(userId, task);
            task.setGoogleEventId(newGoogleId);
            task.setLastSyncedAt(Instant.now());
            return;
        }

        if (response.statusCode() != 200) {
            log.error("Google Event update failed: status={}, body={}", response.statusCode(), response.body());
            throw new RuntimeException("Failed to update event in Google Calendar: " + response.body());
        }
    }

    /**
     * Delete Event from Google Calendar
     */
    public void deleteEvent(String userId, String googleEventId) throws Exception {
        if (googleEventId == null || googleEventId.isBlank()) {
            return;
        }

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create("https://www.googleapis.com/calendar/v3/calendars/primary/events/" + googleEventId))
                .DELETE();

        HttpResponse<String> response = executeRequestWithAuth(userId, requestBuilder);
        if (response.statusCode() != 200 && response.statusCode() != 204 && response.statusCode() != 410 && response.statusCode() != 404) {
            log.error("Google Event deletion failed: status={}, body={}", response.statusCode(), response.body());
            throw new RuntimeException("Failed to delete event from Google Calendar: " + response.body());
        }
    }

    /**
     * Watch events changes (Webhook setup)
     */
    public WatchResponse watchCalendar(String userId, String channelId) throws Exception {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("id", channelId);
        payload.put("type", "web_hook");
        payload.put("address", webhookUrl);

        // Optional expiration (up to 30 days, we let Google assign maximum or request a large value)
        // Instant exp = Instant.now().plus(29, java.time.temporal.ChronoUnit.DAYS);
        // payload.put("expiration", exp.toEpochMilli());

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create("https://www.googleapis.com/calendar/v3/calendars/primary/events/watch"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString()));

        HttpResponse<String> response = executeRequestWithAuth(userId, requestBuilder);
        if (response.statusCode() != 200) {
            log.error("Failed to watch calendar for user {}: {}", userId, response.body());
            throw new RuntimeException("Google Calendar watch failed: " + response.body());
        }

        JsonNode responseNode = objectMapper.readTree(response.body());
        String resourceId = responseNode.get("resourceId").asText();
        Instant expiration = Instant.ofEpochMilli(responseNode.get("expiration").asLong());

        return new WatchResponse(channelId, resourceId, expiration);
    }

    /**
     * Stop a Webhook watch channel
     */
    public void stopChannel(String userId, String channelId, String resourceId) {
        try {
            ObjectNode payload = objectMapper.createObjectNode();
            payload.put("id", channelId);
            payload.put("resourceId", resourceId);

            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create("https://www.googleapis.com/calendar/v3/channels/stop"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload.toString()));

            HttpResponse<String> response = executeRequestWithAuth(userId, requestBuilder);
            if (response.statusCode() != 200 && response.statusCode() != 204 && response.statusCode() != 404) {
                log.warn("Failed to stop watch channel {} for user {}: {}", channelId, userId, response.body());
            }
        } catch (Exception e) {
            log.warn("Exception stopped watch channel {} for user {}: {}", channelId, userId, e.getMessage());
        }
    }

    /**
     * List events (supports incremental sync and pagination)
     */
    public SyncEventsResponse listEvents(String userId, String syncToken, String pageToken) throws Exception {
        StringBuilder urlBuilder = new StringBuilder("https://www.googleapis.com/calendar/v3/calendars/primary/events?maxResults=100");
        if (syncToken != null && !syncToken.isBlank() && pageToken == null) {
            urlBuilder.append("&syncToken=").append(syncToken);
        }
        if (pageToken != null && !pageToken.isBlank()) {
            urlBuilder.append("&pageToken=").append(pageToken);
        }

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(urlBuilder.toString()))
                .GET();

        HttpResponse<String> response = executeRequestWithAuth(userId, requestBuilder);
        
        if (response.statusCode() == 410) {
            log.warn("Sync token expired (410 Gone) for user: {}", userId);
            return new SyncEventsResponse(Collections.emptyList(), null, null, true);
        }

        if (response.statusCode() != 200) {
            log.error("Google Calendar list events failed: status={}, body={}", response.statusCode(), response.body());
            throw new RuntimeException("Google listEvents failed: " + response.body());
        }

        JsonNode responseNode = objectMapper.readTree(response.body());
        List<JsonNode> items = new ArrayList<>();
        if (responseNode.has("items")) {
            for (JsonNode item : responseNode.get("items")) {
                items.add(item);
            }
        }

        String nextSyncToken = responseNode.has("nextSyncToken") ? responseNode.get("nextSyncToken").asText() : null;
        String nextPageToken = responseNode.has("nextPageToken") ? responseNode.get("nextPageToken").asText() : null;

        return new SyncEventsResponse(items, nextSyncToken, nextPageToken, false);
    }

    /**
     * Helper to map our local Task model into a Google Event Resource JSON representation
     */
    private String mapLocalToGoogleEventJson(String userId, DailyTask task) throws Exception {
        ObjectNode event = objectMapper.createObjectNode();
        event.put("summary", task.getTitle());
        event.put("description", task.getNotes() != null ? task.getNotes() : "");

        // Fetch user timezone
        String timeZoneStr = userAccountRepository.findById(userId)
                .map(UserAccount::getTimezone)
                .orElse(ZoneId.systemDefault().getId());

        ObjectNode start = objectMapper.createObjectNode();
        ObjectNode end = objectMapper.createObjectNode();

        if (Boolean.TRUE.equals(task.getAllDay())) {
            start.put("date", task.getDate().toString());
            // Google all-day events exclusive end dates
            end.put("date", task.getDate().plusDays(1).toString());
        } else {
            ZoneId userZone = ZoneId.of(timeZoneStr);
            String startTimeStr = task.getStartTime() != null ? task.getStartTime() : "09:00";
            
            LocalDateTime startLdt = LocalDateTime.of(task.getDate(), java.time.LocalTime.parse(startTimeStr));
            ZonedDateTime startZdt = startLdt.atZone(userZone);
            
            LocalDateTime endLdt;
            if (task.getEndTime() != null) {
                endLdt = LocalDateTime.of(task.getDate(), java.time.LocalTime.parse(task.getEndTime()));
            } else {
                endLdt = startLdt.plusHours(1);
            }
            ZonedDateTime endZdt = endLdt.atZone(userZone);

            start.put("dateTime", startZdt.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
            start.put("timeZone", timeZoneStr);

            end.put("dateTime", endZdt.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
            end.put("timeZone", timeZoneStr);
        }

        event.set("start", start);
        event.set("end", end);

        return objectMapper.writeValueAsString(event);
    }
}
