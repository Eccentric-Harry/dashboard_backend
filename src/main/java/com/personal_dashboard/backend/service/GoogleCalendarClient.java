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
import com.personal_dashboard.backend.util.TimeZoneUtils;
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
    // Not final so tests can inject a mock client via setHttpClient(); Lombok's
    // @RequiredArgsConstructor excludes initialized fields, so Spring wiring is unaffected.
    private HttpClient httpClient = HttpClient.newHttpClient();

    /** Test seam — replace the HTTP client. Not used in production wiring. */
    void setHttpClient(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    /**
     * Deterministic Google event id derived from the local task id. Google event
     * ids must be base32hex (chars 0-9a-v), length 5–1024. A lowercase Mongo
     * ObjectId hex (0-9a-f, length 24) already satisfies this, so we use it verbatim.
     * Supplying it on insert makes the write idempotent: a retry after a lost
     * response gets 409 (already exists) instead of creating a second copy.
     * The SAME id is reused across accounts on purpose — event ids are unique
     * per-calendar, so one local event maps to the same id in every account.
     * Returns null when the id is not a valid client id (caller falls back to a
     * server-generated id, losing idempotency for that one write).
     */
    static String deterministicEventId(String taskId) {
        if (taskId == null) return null;
        String candidate = taskId.toLowerCase(java.util.Locale.ROOT);
        return candidate.matches("[0-9a-v]{5,1024}") ? candidate : null;
    }

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
     * Force-refresh the access token for a specific store (identified by storeId = userId:email).
     */
    public synchronized String refreshAccessToken(String storeId, String encryptedRefreshToken) {
        log.info("Refreshing access token for store: {}", storeId);
        String decryptedRefreshToken = encryptionUtils.decrypt(encryptedRefreshToken);
        if (decryptedRefreshToken == null || decryptedRefreshToken.isBlank()) {
            throw new IllegalStateException("Cannot refresh access token: refresh token is missing");
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
                log.error("Failed to refresh access token for store {}: {}", storeId, response.body());
                if (response.statusCode() == 400 || response.statusCode() == 401) {
                    // Token is revoked — remove this specific account's store
                    syncStoreRepository.deleteById(storeId);
                }
                throw new RuntimeException("Google token refresh failed: " + response.statusCode());
            }

            JsonNode jsonNode = objectMapper.readTree(response.body());
            String newAccessToken = jsonNode.get("access_token").asText();

            GoogleSyncStore store = syncStoreRepository.findById(storeId)
                    .orElseThrow(() -> new IllegalArgumentException("Sync store not found: " + storeId));
            store.setAccessToken(encryptionUtils.encrypt(newAccessToken));
            if (jsonNode.has("refresh_token")) {
                store.setRefreshToken(encryptionUtils.encrypt(jsonNode.get("refresh_token").asText()));
            }

            syncStoreRepository.save(store);
            log.info("Successfully refreshed access token for store: {}", storeId);
            return newAccessToken;

        } catch (Exception e) {
            log.error("Error refreshing token for store {}", storeId, e);
            throw new RuntimeException("Failed to refresh token", e);
        }
    }

    /**
     * Retrieve a valid access token for the given storeId (userId:email).
     */
    public String getValidAccessToken(String storeId) {
        GoogleSyncStore store = syncStoreRepository.findById(storeId)
                .orElseThrow(() -> new IllegalArgumentException("Google credentials not found for store: " + storeId));
        return encryptionUtils.decrypt(store.getAccessToken());
    }

    /**
     * Execute an HTTP request with automatic token refresh on 401.
     * storeId = userId:email — used to resolve credentials.
     */
    private HttpResponse<String> executeRequestWithAuth(String storeId, HttpRequest.Builder requestBuilder) throws Exception {
        String accessToken = getValidAccessToken(storeId);
        HttpRequest request = requestBuilder.copy()
                .header("Authorization", "Bearer " + accessToken)
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 401) {
            log.warn("Access token expired for store {}. Refreshing and retrying.", storeId);
            GoogleSyncStore store = syncStoreRepository.findById(storeId)
                    .orElseThrow(() -> new IllegalArgumentException("Google sync store not found: " + storeId));
            String newAccessToken = refreshAccessToken(storeId, store.getRefreshToken());
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
     * Insert a new event into Google Calendar.
     * storeId = userId:email — identifies which account's credentials to use.
     */
    public String insertEvent(String storeId, DailyTask task) throws Exception {
        ObjectNode event = buildEventNode(storeId, task);

        // Idempotency: supply a deterministic client id so a retried insert (after a
        // lost response) returns 409 instead of creating a duplicate event.
        String clientId = deterministicEventId(task.getId());
        if (clientId != null) {
            event.put("id", clientId);
        }

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create("https://www.googleapis.com/calendar/v3/calendars/primary/events"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(event)));

        HttpResponse<String> response = executeRequestWithAuth(storeId, requestBuilder);

        if (response.statusCode() == 409) {
            // The event already exists — a previous insert with this same client id
            // succeeded but we never saw the response. This is a SUCCESSFUL retry,
            // not a duplicate. If the local task is already soft-deleted, the change
            // is fully handled; either way we return the id we sent without looping.
            if (clientId != null) {
                log.info("Insert got 409 (already exists) for event id {}{} — treating as success (idempotent).",
                        clientId, Boolean.TRUE.equals(task.getDeleted()) ? " [soft-deleted, already handled]" : "");
                return clientId;
            }
            log.error("Google Event insert returned 409 but no client id was supplied: {}", response.body());
            throw new RuntimeException("Failed to insert event to Google Calendar (409, no client id): " + response.body());
        }

        if (response.statusCode() != 200 && response.statusCode() != 201) {
            log.error("Google Event insert failed: status={}, body={}", response.statusCode(), response.body());
            throw new RuntimeException("Failed to insert event to Google Calendar: " + response.body());
        }

        JsonNode responseNode = objectMapper.readTree(response.body());
        return responseNode.get("id").asText();
    }

    /**
     * Update an existing Google Calendar event by its googleEventId.
     * storeId = userId:email — identifies which account's credentials to use.
     * googleEventId is passed explicitly from the CalendarSyncMapping, not from task.
     */
    public String updateEvent(String storeId, String googleEventId, DailyTask task) throws Exception {
        if (googleEventId == null || googleEventId.isBlank()) {
            throw new IllegalArgumentException("googleEventId must be provided for update");
        }

        // No client id on update: the path already carries the event id, and a
        // Google-origin event's id is NOT our taskId, so injecting one would be wrong.
        String eventJson = objectMapper.writeValueAsString(buildEventNode(storeId, task));
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create("https://www.googleapis.com/calendar/v3/calendars/primary/events/" + googleEventId))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(eventJson));

        HttpResponse<String> response = executeRequestWithAuth(storeId, requestBuilder);
        if (response.statusCode() == 404) {
            // Event was deleted in Google Calendar — re-insert it and return the new ID.
            log.warn("Google Event {} not found during update (404). Re-inserting.", googleEventId);
            return insertEvent(storeId, task);
        }

        if (response.statusCode() != 200) {
            log.error("Google Event update failed: status={}, body={}", response.statusCode(), response.body());
            throw new RuntimeException("Failed to update event in Google Calendar: " + response.body());
        }
        return googleEventId; // unchanged
    }

    /**
     * Delete an event from Google Calendar.
     * storeId = userId:email.
     */
    public void deleteEvent(String storeId, String googleEventId) throws Exception {
        if (googleEventId == null || googleEventId.isBlank()) {
            return;
        }

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create("https://www.googleapis.com/calendar/v3/calendars/primary/events/" + googleEventId))
                .DELETE();

        HttpResponse<String> response = executeRequestWithAuth(storeId, requestBuilder);
        if (response.statusCode() != 200 && response.statusCode() != 204 && response.statusCode() != 410 && response.statusCode() != 404) {
            log.error("Google Event deletion failed: status={}, body={}", response.statusCode(), response.body());
            throw new RuntimeException("Failed to delete event from Google Calendar: " + response.body());
        }
    }

    /**
     * Register a push-notification watch channel for the given storeId.
     */
    public WatchResponse watchCalendar(String storeId, String channelId) throws Exception {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("id", channelId);
        payload.put("type", "web_hook");
        payload.put("address", webhookUrl);

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create("https://www.googleapis.com/calendar/v3/calendars/primary/events/watch"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString()));

        HttpResponse<String> response = executeRequestWithAuth(storeId, requestBuilder);
        if (response.statusCode() != 200) {
            log.error("Failed to watch calendar for store {}: {}", storeId, response.body());
            throw new RuntimeException("Google Calendar watch failed: " + response.body());
        }

        JsonNode responseNode = objectMapper.readTree(response.body());
        String resourceId = responseNode.get("resourceId").asText();
        Instant expiration = Instant.ofEpochMilli(responseNode.get("expiration").asLong());

        return new WatchResponse(channelId, resourceId, expiration);
    }

    /**
     * Stop a webhook watch channel.
     */
    public void stopChannel(String storeId, String channelId, String resourceId) {
        try {
            ObjectNode payload = objectMapper.createObjectNode();
            payload.put("id", channelId);
            payload.put("resourceId", resourceId);

            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create("https://www.googleapis.com/calendar/v3/channels/stop"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload.toString()));

            HttpResponse<String> response = executeRequestWithAuth(storeId, requestBuilder);
            if (response.statusCode() != 200 && response.statusCode() != 204 && response.statusCode() != 404) {
                log.warn("Failed to stop watch channel {} for store {}: {}", channelId, storeId, response.body());
            }
        } catch (Exception e) {
            log.warn("Exception stopping watch channel {} for store {}: {}", channelId, storeId, e.getMessage());
        }
    }

    /**
     * List calendar events with incremental-sync / pagination support.
     * storeId = userId:email.
     */
    public SyncEventsResponse listEvents(String storeId, String syncToken, String pageToken) throws Exception {
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

        HttpResponse<String> response = executeRequestWithAuth(storeId, requestBuilder);

        if (response.statusCode() == 410) {
            log.warn("Sync token expired (410 Gone) for store: {}", storeId);
            return new SyncEventsResponse(Collections.emptyList(), null, null, true);
        }

        if (response.statusCode() != 200) {
            log.error("Google Calendar listEvents failed: status={}, body={}", response.statusCode(), response.body());
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
     * Build the Google Event JSON body for a local DailyTask (without an id).
     * storeId is used to resolve the user's timezone via their UserAccount.
     * Callers add a client id only for inserts.
     */
    private ObjectNode buildEventNode(String storeId, DailyTask task) throws Exception {
        // Resolve userId from the store so we can look up the user's timezone preference
        GoogleSyncStore store = syncStoreRepository.findById(storeId)
                .orElseThrow(() -> new IllegalArgumentException("Sync store not found: " + storeId));
        String userId = store.getUserId();

        ObjectNode event = objectMapper.createObjectNode();
        event.put("summary", task.getTitle());
        event.put("description", task.getNotes() != null ? task.getNotes() : "");

        // Fetch user timezone
        String rawTimeZoneStr = userAccountRepository.findById(userId)
                .map(UserAccount::getTimezone)
                .orElse(ZoneId.systemDefault().getId());
        String timeZoneStr = TimeZoneUtils.normalizeTimeZone(rawTimeZoneStr);

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

        return event;
    }
}
