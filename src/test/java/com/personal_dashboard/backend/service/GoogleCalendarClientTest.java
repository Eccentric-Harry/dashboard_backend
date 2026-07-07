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
