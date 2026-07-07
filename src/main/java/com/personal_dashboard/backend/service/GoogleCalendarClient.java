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
                    // Refresh token revoked/invalid — mark the account DISCONNECTED instead of
                    // deleting it, so the user sees a clean "reconnect needed" state.
                    markDisconnected(storeId, "Refresh token revoked or invalid (HTTP " + response.statusCode() + ")");
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

    /** Mark an account DISCONNECTED (revoked/invalid credentials) without deleting it. */
    private void markDisconnected(String storeId, String reason) {
        syncStoreRepository.findById(storeId).ifPresent(store -> {
            store.setStatus("DISCONNECTED");
            store.setAuthError(reason);
            store.setDisconnectedAt(Instant.now());
            syncStoreRepository.save(store);
            log.warn("Google account {} marked DISCONNECTED: {}", storeId, reason);
        });
    }

    /**
     * Retrieve a valid access token for the given storeId (userId:email).
     */
    public String getValidAccessToken(String storeId) {
        GoogleSyncStore store = syncStoreRepository.findById(storeId)
                .orElseThrow(() -> new IllegalArgumentException("Google credentials not found for store: " + storeId));
        return encryptionUtils.decrypt(store.getAccessToken());
    }

    private static final int MAX_ATTEMPTS = 5;
    // Base backoff; settable to 0 in tests so the retry loop doesn't actually sleep.
    private long retryBaseDelayMs = 500L;

    void setRetryBaseDelayMs(long ms) {
        this.retryBaseDelayMs = ms;
    }

    /**
     * Execute an HTTP request with automatic token refresh on 401 and bounded
     * retries with exponential backoff + full jitter on transient failures
     * (429, 5xx, and 403 rateLimitExceeded/userRateLimitExceeded).
     */
    private HttpResponse<String> executeRequestWithAuth(String storeId, HttpRequest.Builder requestBuilder) throws Exception {
        String accessToken = getValidAccessToken(storeId);
        boolean refreshed = false;

        for (int attempt = 1; ; attempt++) {
            HttpRequest request = requestBuilder.copy()
                    .header("Authorization", "Bearer " + accessToken)
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 401 && !refreshed) {
                log.warn("Access token expired for store {}. Refreshing and retrying.", storeId);
                GoogleSyncStore store = syncStoreRepository.findById(storeId)
                        .orElseThrow(() -> new IllegalArgumentException("Google sync store not found: " + storeId));
                accessToken = refreshAccessToken(storeId, store.getRefreshToken());
                refreshed = true;
                continue; // re-send with the fresh token
            }

            if (isRetryable(response) && attempt < MAX_ATTEMPTS) {
                long backoff = backoffMillis(attempt);
                log.warn("Retryable {} from Google for store {} (attempt {}/{}); backing off {}ms",
                        response.statusCode(), storeId, attempt, MAX_ATTEMPTS, backoff);
                if (backoff > 0) {
                    Thread.sleep(backoff);
                }
                continue;
            }

            return response;
        }
    }

    /** 429, any 5xx, or 403 that is specifically a rate-limit (not a hard forbidden). */
    private boolean isRetryable(HttpResponse<String> response) {
        int code = response.statusCode();
        if (code == 429) return true;
        if (code >= 500 && code < 600) return true;
        if (code == 403) {
            String body = response.body();
            return body != null
                    && (body.contains("rateLimitExceeded") || body.contains("userRateLimitExceeded"));
        }
        return false;
    }

    /** Exponential backoff with full jitter over [0, cappedBase], capped at 32s. */
    private long backoffMillis(int attempt) {
        long exp = retryBaseDelayMs << Math.min(attempt - 1, 6); // cap the shift
        long capped = Math.min(exp, 32_000L);
        return capped <= 0 ? 0 : java.util.concurrent.ThreadLocalRandom.current().nextLong(capped + 1);
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

    /** Outcome of an update push: the (possibly re-inserted) event id, its new etag,
     *  and whether our local change was actually applied (false = remote won LWW). */
    public record UpdateResult(String googleEventId, String etag, boolean applied) {}

    /**
     * Update an existing Google Calendar event, with concurrent-edit detection.
     *
     * Sends If-Match: <etag>. A 412 (Precondition Failed) means the remote copy
     * changed under us since we last synced. We then resolve last-write-wins by
     * comparing Google's current `updated` against our local `updatedAt`:
     *   - local newer  → force-overwrite the remote (our push wins).
     *   - remote newer → skip the push (applied=false); the next pull reconciles
     *                    the remote change into the local task.
     * Both clocks are logged on every conflict (they are DIFFERENT clocks).
     */
    public UpdateResult updateEvent(String storeId, String googleEventId, DailyTask task, String etag) throws Exception {
        if (googleEventId == null || googleEventId.isBlank()) {
            throw new IllegalArgumentException("googleEventId must be provided for update");
        }

        String eventJson = objectMapper.writeValueAsString(buildEventNode(storeId, task));
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create("https://www.googleapis.com/calendar/v3/calendars/primary/events/" + googleEventId))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(eventJson));
        if (etag != null && !etag.isBlank()) {
            requestBuilder.header("If-Match", etag);
        }

        HttpResponse<String> response = executeRequestWithAuth(storeId, requestBuilder);

        if (response.statusCode() == 404) {
            log.warn("Google Event {} not found during update (404). Re-inserting.", googleEventId);
            return new UpdateResult(insertEvent(storeId, task), null, true);
        }

        if (response.statusCode() == 412) {
            return resolveConflict(storeId, googleEventId, task, eventJson);
        }

        if (response.statusCode() != 200) {
            log.error("Google Event update failed: status={}, body={}", response.statusCode(), response.body());
            throw new RuntimeException("Failed to update event in Google Calendar: " + response.body());
        }
        return new UpdateResult(googleEventId, readEtag(response.body()), true);
    }

    /** Last-write-wins resolution after a 412 on update. */
    private UpdateResult resolveConflict(String storeId, String googleEventId, DailyTask task, String eventJson) throws Exception {
        JsonNode remote = fetchEvent(storeId, googleEventId);
        if (remote == null) {
            // Vanished remotely between our PUT and the refetch — re-insert.
            log.warn("Conflict refetch for event {} returned nothing — re-inserting.", googleEventId);
            return new UpdateResult(insertEvent(storeId, task), null, true);
        }

        Instant remoteUpdated = remote.has("updated") ? Instant.parse(remote.get("updated").asText()) : Instant.EPOCH;
        Instant localUpdated = task.getUpdatedAt();
        boolean localWins = localUpdated != null && localUpdated.isAfter(remoteUpdated);
        log.warn("Conflict on event {} [remoteUpdated={} localUpdatedAt={}] — last-write-wins: {}",
                googleEventId, remoteUpdated, localUpdated, localWins ? "LOCAL" : "REMOTE");

        if (!localWins) {
            // Remote wins: don't overwrite. Return remote etag so the mapping tracks it;
            // the next pull brings the remote content into the local task.
            return new UpdateResult(googleEventId, readText(remote, "etag"), false);
        }

        // Local wins: force-overwrite unconditionally (no If-Match).
        HttpRequest.Builder forced = HttpRequest.newBuilder()
                .uri(URI.create("https://www.googleapis.com/calendar/v3/calendars/primary/events/" + googleEventId))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(eventJson));
        HttpResponse<String> resp = executeRequestWithAuth(storeId, forced);
        if (resp.statusCode() == 404) {
            return new UpdateResult(insertEvent(storeId, task), null, true);
        }
        if (resp.statusCode() != 200) {
            log.error("Forced conflict overwrite failed for event {}: status={}, body={}",
                    googleEventId, resp.statusCode(), resp.body());
            throw new RuntimeException("Failed to resolve conflict for event " + googleEventId);
        }
        return new UpdateResult(googleEventId, readEtag(resp.body()), true);
    }

    /** GET a single event; returns null on 404/410 (gone). */
    private JsonNode fetchEvent(String storeId, String googleEventId) throws Exception {
        HttpRequest.Builder rb = HttpRequest.newBuilder()
                .uri(URI.create("https://www.googleapis.com/calendar/v3/calendars/primary/events/" + googleEventId))
                .GET();
        HttpResponse<String> resp = executeRequestWithAuth(storeId, rb);
        if (resp.statusCode() == 404 || resp.statusCode() == 410) {
            return null;
        }
        if (resp.statusCode() != 200) {
            throw new RuntimeException("Failed to fetch event " + googleEventId + ": " + resp.body());
        }
        return objectMapper.readTree(resp.body());
    }

    private String readEtag(String body) {
        try {
            return readText(objectMapper.readTree(body), "etag");
        } catch (Exception e) {
            return null;
        }
    }

    private String readText(JsonNode node, String field) {
        if (node == null || !node.has(field) || node.get(field).isNull()) return null;
        String v = node.get(field).asText();
        return (v == null || v.isBlank()) ? null : v;
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

        // Prefer the event's own captured IANA zone; otherwise fall back to the
        // user's configured timezone, then the system default.
        String rawTimeZoneStr = task.getTimeZone();
        if (rawTimeZoneStr == null || rawTimeZoneStr.isBlank()) {
            rawTimeZoneStr = userAccountRepository.findById(userId)
                    .map(UserAccount::getTimezone)
                    .orElse(ZoneId.systemDefault().getId());
        }
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

        // Recurring task → emit an RFC 5545 recurrence array (RRULE + optional EXDATE).
        if (task.isRecurring()) {
            com.fasterxml.jackson.databind.node.ArrayNode recurrence = objectMapper.createArrayNode();
            recurrence.add(buildRrule(task, timeZoneStr));
            String exdate = buildExdate(task, timeZoneStr);
            if (exdate != null) {
                recurrence.add(exdate);
            }
            event.set("recurrence", recurrence);
        }

        return event;
    }

    private static final DateTimeFormatter BASIC_DATE = DateTimeFormatter.BASIC_ISO_DATE; // yyyyMMdd
    private static final DateTimeFormatter RRULE_UNTIL_UTC = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'");
    private static final DateTimeFormatter EXDATE_TIME = DateTimeFormatter.ofPattern("HHmmss");

    /** Build an RRULE line (FREQ + optional UNTIL) from the task's recurrence. */
    static String buildRrule(DailyTask task, String timeZoneStr) {
        String freq = task.getRecurrenceFrequency().toUpperCase(java.util.Locale.ROOT); // DAILY|WEEKLY|MONTHLY
        StringBuilder rule = new StringBuilder("RRULE:FREQ=").append(freq);
        LocalDate until = task.getRecurrenceUntil();
        if (until != null) {
            if (Boolean.TRUE.equals(task.getAllDay())) {
                // DATE value for all-day series.
                rule.append(";UNTIL=").append(until.format(BASIC_DATE));
            } else {
                // Timed series: UNTIL must be a UTC datetime. Take end-of-day in the
                // event's zone so the final day is inclusive.
                ZonedDateTime utc = until.atTime(23, 59, 59)
                        .atZone(ZoneId.of(timeZoneStr))
                        .withZoneSameInstant(java.time.ZoneOffset.UTC);
                rule.append(";UNTIL=").append(utc.format(RRULE_UNTIL_UTC));
            }
        }
        return rule.toString();
    }

    /** Build an EXDATE line from the task's excludedDates (skipped occurrences), or null. */
    static String buildExdate(DailyTask task, String timeZoneStr) {
        List<LocalDate> excluded = task.getExcludedDates();
        if (excluded == null || excluded.isEmpty()) {
            return null;
        }
        if (Boolean.TRUE.equals(task.getAllDay())) {
            StringBuilder sb = new StringBuilder("EXDATE;VALUE=DATE:");
            for (int i = 0; i < excluded.size(); i++) {
                if (i > 0) sb.append(",");
                sb.append(excluded.get(i).format(BASIC_DATE));
            }
            return sb.toString();
        }
        // Timed: each excluded date at the event's start time in its zone.
        java.time.LocalTime startTime = task.getStartTime() != null
                ? java.time.LocalTime.parse(task.getStartTime())
                : java.time.LocalTime.of(9, 0);
        String time = startTime.format(EXDATE_TIME);
        StringBuilder sb = new StringBuilder("EXDATE;TZID=").append(timeZoneStr).append(":");
        for (int i = 0; i < excluded.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append(excluded.get(i).format(BASIC_DATE)).append("T").append(time);
        }
        return sb.toString();
    }
}
