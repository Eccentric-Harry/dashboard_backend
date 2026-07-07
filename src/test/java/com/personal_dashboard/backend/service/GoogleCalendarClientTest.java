package com.personal_dashboard.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.personal_dashboard.backend.model.DailyTask;
import com.personal_dashboard.backend.model.GoogleSyncStore;
import com.personal_dashboard.backend.repository.GoogleSyncStoreRepository;
import com.personal_dashboard.backend.repository.UserAccountRepository;
import com.personal_dashboard.backend.util.EncryptionUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Idempotent insert path (commit 4): deterministic client ids + 409-as-success.
 */
@ExtendWith(MockitoExtension.class)
class GoogleCalendarClientTest {

    private static final String STORE_ID = "u:acct@gmail.com";
    private static final String TASK_ID = "5f9a1b2c3d4e5f6a7b8c9d0e"; // 24-char Mongo ObjectId hex

    @Mock private GoogleSyncStoreRepository syncStoreRepository;
    @Mock private UserAccountRepository userAccountRepository;
    @Mock private EncryptionUtils encryptionUtils;
    @Mock private HttpClient httpClient;

    private GoogleCalendarClient client;

    @BeforeEach
    void setUp() {
        client = new GoogleCalendarClient(syncStoreRepository, userAccountRepository,
                encryptionUtils, new ObjectMapper());
        client.setHttpClient(httpClient);
        client.setRetryBaseDelayMs(0); // no real sleeping in tests
    }

    // ── deterministicEventId contract (guardrails #2, #3) ──────────────────────

    @Test
    void deterministicEventId_lowercasesValidObjectId() {
        assertEquals("5f9a1b2c3d4e5f6a7b8c9d0e",
                GoogleCalendarClient.deterministicEventId("5F9A1B2C3D4E5F6A7B8C9D0E"));
    }

    @Test
    void deterministicEventId_rejectsInvalidCharsAndLengths() {
        assertNull(GoogleCalendarClient.deterministicEventId(null));
        assertNull(GoogleCalendarClient.deterministicEventId("abc"));           // too short
        assertNull(GoogleCalendarClient.deterministicEventId("has-a-hyphen!")); // out of charset
        assertNull(GoogleCalendarClient.deterministicEventId("wxyz-uppercase-Z-out-of-base32hex"));
    }

    // ── 409-as-success: the classic lost-response retry must not duplicate ─────

    @Test
    @SuppressWarnings("unchecked")
    void insert_returning409_isTreatedAsSuccessWithClientId() throws Exception {
        stubAuth();
        HttpResponse<String> resp = mock(HttpResponse.class);
        when(resp.statusCode()).thenReturn(409);
        when(httpClient.<String>send(any(HttpRequest.class), any())).thenReturn(resp);

        String returned = client.insertEvent(STORE_ID, task());

        // No exception, and the id we get back is exactly the deterministic client id —
        // so the caller records the mapping instead of retrying into a duplicate.
        assertEquals(TASK_ID, returned);
        // Exactly one network call — no re-insert loop.
        verify(httpClient, times(1)).send(any(HttpRequest.class), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void insert_success_returnsServerId() throws Exception {
        stubAuth();
        HttpResponse<String> resp = mock(HttpResponse.class);
        when(resp.statusCode()).thenReturn(200);
        when(resp.body()).thenReturn("{\"id\":\"" + TASK_ID + "\"}");
        when(httpClient.<String>send(any(HttpRequest.class), any())).thenReturn(resp);

        assertEquals(TASK_ID, client.insertEvent(STORE_ID, task()));
    }

    // ── Conflict resolution: 412 → last-write-wins ────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void update_conflict_localWins_forceOverwrites() throws Exception {
        stubAuth();
        HttpResponse<String> precondFail = mock(HttpResponse.class);
        when(precondFail.statusCode()).thenReturn(412);
        HttpResponse<String> remoteGet = mock(HttpResponse.class);
        when(remoteGet.statusCode()).thenReturn(200);
        when(remoteGet.body()).thenReturn("{\"id\":\"G1\",\"updated\":\"2026-07-01T09:00:00.000Z\",\"etag\":\"\\\"old\\\"\"}");
        HttpResponse<String> forced = mock(HttpResponse.class);
        when(forced.statusCode()).thenReturn(200);
        when(forced.body()).thenReturn("{\"id\":\"G1\",\"etag\":\"\\\"new\\\"\"}");
        // PUT(If-Match)=412, GET=200, PUT(force)=200
        when(httpClient.<String>send(any(HttpRequest.class), any()))
                .thenReturn(precondFail, remoteGet, forced);

        DailyTask t = task();
        t.setUpdatedAt(java.time.Instant.parse("2026-07-01T10:00:00.000Z")); // local NEWER than remote
        GoogleCalendarClient.UpdateResult r = client.updateEvent(STORE_ID, "G1", t, "\"held\"");

        assertTrue(r.applied());
        assertEquals("G1", r.googleEventId());
        verify(httpClient, times(3)).send(any(HttpRequest.class), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void update_conflict_remoteWins_skipsPush() throws Exception {
        stubAuth();
        HttpResponse<String> precondFail = mock(HttpResponse.class);
        when(precondFail.statusCode()).thenReturn(412);
        HttpResponse<String> remoteGet = mock(HttpResponse.class);
        when(remoteGet.statusCode()).thenReturn(200);
        when(remoteGet.body()).thenReturn("{\"id\":\"G1\",\"updated\":\"2026-07-01T12:00:00.000Z\",\"etag\":\"\\\"remote\\\"\"}");
        when(httpClient.<String>send(any(HttpRequest.class), any()))
                .thenReturn(precondFail, remoteGet);

        DailyTask t = task();
        t.setUpdatedAt(java.time.Instant.parse("2026-07-01T10:00:00.000Z")); // local OLDER than remote
        GoogleCalendarClient.UpdateResult r = client.updateEvent(STORE_ID, "G1", t, "\"held\"");

        assertFalse(r.applied());              // remote won → not overwritten
        assertEquals("\"remote\"", r.etag());  // mapping tracks remote etag
        verify(httpClient, times(2)).send(any(HttpRequest.class), any()); // PUT + GET, no force
    }

    // ── Backoff: transient 429 is retried, then succeeds ──────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void insert_retriesOn429ThenSucceeds() throws Exception {
        stubAuth();
        HttpResponse<String> rateLimited = mock(HttpResponse.class);
        when(rateLimited.statusCode()).thenReturn(429);
        HttpResponse<String> ok = mock(HttpResponse.class);
        when(ok.statusCode()).thenReturn(200);
        when(ok.body()).thenReturn("{\"id\":\"" + TASK_ID + "\"}");
        when(httpClient.<String>send(any(HttpRequest.class), any())).thenReturn(rateLimited, ok);

        assertEquals(TASK_ID, client.insertEvent(STORE_ID, task()));
        verify(httpClient, times(2)).send(any(HttpRequest.class), any());
    }

    private void stubAuth() {
        GoogleSyncStore store = GoogleSyncStore.builder()
                .id(STORE_ID).userId("u").email("acct@gmail.com").accessToken("enc").build();
        when(syncStoreRepository.findById(STORE_ID)).thenReturn(Optional.of(store));
        when(encryptionUtils.decrypt("enc")).thenReturn("plain-token");
        when(userAccountRepository.findById("u")).thenReturn(Optional.empty());
    }

    private DailyTask task() {
        return DailyTask.builder()
                .id(TASK_ID).userId("u").title("Standup")
                .date(LocalDate.of(2026, 7, 2)).allDay(true)
                .build();
    }
}
