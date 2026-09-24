package com.personal_dashboard.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.personal_dashboard.backend.model.GoogleSyncStore;
import com.personal_dashboard.backend.repository.GoogleSyncStoreRepository;
import com.personal_dashboard.backend.util.EncryptionUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Sends authenticated requests to any Google API on behalf of a connected account,
 * handling the two things every caller would otherwise reimplement: refreshing an
 * expired access token exactly once per request, and retrying transient failures
 * with exponential backoff plus full jitter.
 *
 * <p>Keyed by {@code storeId} (= userId:email), so one connected account's credentials
 * are resolved, refreshed and — when Google rejects the refresh token outright —
 * marked DISCONNECTED in one place.
 *
 * <p>Note: {@link GoogleCalendarClient} still carries its own copy of this logic.
 * It was left alone on purpose — it is load-bearing, covered by tests that construct
 * it directly, and rewriting it was not worth destabilising working calendar sync to
 * add a feature beside it. Migrating Calendar onto this executor is a clean follow-up.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class GoogleApiExecutor {

    private static final int MAX_ATTEMPTS = 5;

    private final GoogleSyncStoreRepository syncStoreRepository;
    private final EncryptionUtils encryptionUtils;
    private final ObjectMapper objectMapper;

    @Value("${google.calendar.client-id}")
    private String clientId;

    @Value("${google.calendar.client-secret}")
    private String clientSecret;

    private HttpClient httpClient = HttpClient.newHttpClient();
    private long retryBaseDelayMs = 500L;

    /** Test seam — replace the HTTP client. Not used in production wiring. */
    void setHttpClient(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    /** Test seam — set to 0 so the retry loop doesn't actually sleep. */
    void setRetryBaseDelayMs(long ms) {
        this.retryBaseDelayMs = ms;
    }

    /** Raised when the account's grant is missing the scope an API needs. */
    public static class MissingScopeException extends RuntimeException {
        public MissingScopeException(String message) {
            super(message);
        }
    }

    /**
     * Execute a request for the given account, refreshing the access token on a 401
     * and retrying transient failures.
     *
     * @throws MissingScopeException when Google reports the grant lacks the needed scope —
     *         a permanent failure that retrying cannot fix, so it is surfaced to the caller
     *         (and on to the user as "reconnect") rather than burned through the retry budget.
     */
    public HttpResponse<String> execute(String storeId, HttpRequest.Builder requestBuilder) throws Exception {
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
                continue;
            }

            if (isMissingScope(response)) {
                throw new MissingScopeException(
                        "Google rejected the request for " + storeId + " — the grant is missing a required scope. "
                                + "The account must be reconnected to approve it.");
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

    /**
     * A 403 whose body names an insufficient scope. Distinct from a rate-limit 403,
     * which is retryable — the two share a status code and nothing else.
     */
    private boolean isMissingScope(HttpResponse<String> response) {
        if (response.statusCode() != 403 && response.statusCode() != 401) {
            return false;
        }
        String body = response.body();
        return body != null
                && (body.contains("ACCESS_TOKEN_SCOPE_INSUFFICIENT")
                    || body.contains("insufficientPermissions")
                    || body.contains("insufficient authentication scopes"));
    }

    /** 429, any 5xx, or a 403 that is specifically a rate limit (not a hard forbidden). */
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
        long exp = retryBaseDelayMs << Math.min(attempt - 1, 6);
        long capped = Math.min(exp, 32_000L);
        return capped <= 0 ? 0 : ThreadLocalRandom.current().nextLong(capped + 1);
    }

    public String getValidAccessToken(String storeId) {
        GoogleSyncStore store = syncStoreRepository.findById(storeId)
                .orElseThrow(() -> new IllegalArgumentException("Google credentials not found for store: " + storeId));
        return encryptionUtils.decrypt(store.getAccessToken());
    }

    public synchronized String refreshAccessToken(String storeId, String encryptedRefreshToken) {
        String decryptedRefreshToken = encryptionUtils.decrypt(encryptedRefreshToken);
        if (decryptedRefreshToken == null || decryptedRefreshToken.isBlank()) {
            throw new IllegalStateException("Cannot refresh access token: refresh token is missing");
        }

        try {
            String requestBody = String.format(
                    "client_id=%s&client_secret=%s&refresh_token=%s&grant_type=refresh_token",
                    clientId, clientSecret, decryptedRefreshToken);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://oauth2.googleapis.com/token"))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.error("Failed to refresh access token for store {}: {}", storeId, response.body());
                if (response.statusCode() == 400 || response.statusCode() == 401) {
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
            log.info("Refreshed access token for store: {}", storeId);
            return newAccessToken;

        } catch (Exception e) {
            log.error("Error refreshing token for store {}", storeId, e);
            throw new RuntimeException("Failed to refresh token", e);
        }
    }

    /** Mark an account DISCONNECTED (revoked/invalid credentials) without deleting it. */
    public void markDisconnected(String storeId, String reason) {
        syncStoreRepository.findById(storeId).ifPresent(store -> {
            store.setStatus("DISCONNECTED");
            store.setAuthError(reason);
            store.setDisconnectedAt(Instant.now());
            syncStoreRepository.save(store);
            log.warn("Google account {} marked DISCONNECTED: {}", storeId, reason);
        });
    }
}
