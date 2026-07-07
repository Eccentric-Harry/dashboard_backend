package com.personal_dashboard.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.personal_dashboard.backend.model.CalendarSyncMapping;
import com.personal_dashboard.backend.model.DailyTask;
import com.personal_dashboard.backend.model.EventOrigin;
import com.personal_dashboard.backend.model.GoogleSyncStore;
import com.personal_dashboard.backend.repository.CalendarSyncMappingRepository;
import com.personal_dashboard.backend.repository.DailyTaskRepository;
import com.personal_dashboard.backend.repository.GoogleSyncStoreRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Dedup precedence + tombstone resurrection guard (commit 3).
 * Drives the public syncCalendar() with a mocked Google client and repositories.
 */
@ExtendWith(MockitoExtension.class)
class GoogleSyncServiceTest {

    private static final String USER = "user1";
    private static final String EMAIL = "user1@gmail.com";
    private static final String STORE_ID = USER + ":" + EMAIL;

    @Mock private GoogleCalendarClient googleCalendarClient;
    @Mock private GoogleSyncStoreRepository syncStoreRepository;
    @Mock private DailyTaskRepository dailyTaskRepository;
    @Mock private CalendarSyncMappingRepository mappingRepository;

    @InjectMocks private GoogleSyncService service;

    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        GoogleSyncStore store = GoogleSyncStore.builder()
                .id(STORE_ID).userId(USER).email(EMAIL).build();
        when(syncStoreRepository.findById(STORE_ID)).thenReturn(Optional.of(store));
    }

    private JsonNode event(String id, String status, String iCalUID) throws Exception {
        String json = String.format(
                "{\"id\":\"%s\",\"status\":\"%s\",\"updated\":\"2026-07-01T10:00:00.000Z\","
                        + "\"etag\":\"\\\"etag-%s\\\"\",\"iCalUID\":\"%s\"}",
                id, status, id, iCalUID);
        return mapper.readTree(json);
    }

    private void stubSinglePull(JsonNode... events) throws Exception {
        GoogleCalendarClient.SyncEventsResponse resp =
                new GoogleCalendarClient.SyncEventsResponse(List.of(events), "next-token", null, false);
        when(googleCalendarClient.listEvents(any(), any(), any())).thenReturn(resp);
    }

    private DailyTask tombstonedTask(String id, String iCalUID) {
        DailyTask t = DailyTask.builder()
                .id(id).userId(USER).title("Gone").iCalUID(iCalUID)
                .origin(EventOrigin.google(EMAIL, "primary"))
                .deleted(true).deletedAt(Instant.now())
                .build();
        return t;
    }

    // ── Guardrail #1: deleted event does not resurrect on full resync ──────────

    @Test
    void tombstonedTask_matchedByGoogleEventId_isNotResurrected() throws Exception {
        DailyTask tomb = tombstonedTask("T1", "uid-1");
        CalendarSyncMapping mapping = CalendarSyncMapping.builder()
                .id("T1:" + EMAIL).taskId("T1").userId(USER).calendarEmail(EMAIL)
                .googleEventId("G1").syncState("CANCELLED").build();
        when(mappingRepository.findByGoogleEventIdAndUserId("G1", USER)).thenReturn(Optional.of(mapping));
        when(dailyTaskRepository.findById("T1")).thenReturn(Optional.of(tomb));
        stubSinglePull(event("G1", "confirmed", "uid-1"));

        service.syncCalendar(USER, EMAIL, true);

        // No task write at all — the tombstone stays deleted, nothing re-created.
        verify(dailyTaskRepository, never()).save(any());
        assertTrue(tomb.getDeleted());
    }

    @Test
    void tombstonedTask_matchedByICalUID_isNotResurrectedAndLinkedCancelled() throws Exception {
        DailyTask tomb = tombstonedTask("T1", "uid-1");
        // No mapping for this googleEventId → falls through to iCalUID lookup.
        when(mappingRepository.findByGoogleEventIdAndUserId("G2", USER)).thenReturn(Optional.empty());
        when(dailyTaskRepository.findByUserAndICalUID(USER, "uid-1")).thenReturn(List.of(tomb));
        stubSinglePull(event("G2", "confirmed", "uid-1"));

        service.syncCalendar(USER, EMAIL, true);

        // Not resurrected, not duplicated…
        verify(dailyTaskRepository, never()).save(any());
        // …and a CANCELLED mapping is written so the next pull short-circuits on G2.
        ArgumentCaptor<CalendarSyncMapping> cap = ArgumentCaptor.forClass(CalendarSyncMapping.class);
        verify(mappingRepository).save(cap.capture());
        assertEquals("T1", cap.getValue().getTaskId());
        assertEquals("G2", cap.getValue().getGoogleEventId());
        assertEquals("CANCELLED", cap.getValue().getSyncState());
    }

    // ── Guardrail: shared invite dedups by iCalUID instead of duplicating ──────

    @Test
    void liveTask_matchedByICalUID_isLinkedNotDuplicated() throws Exception {
        DailyTask live = DailyTask.builder()
                .id("T1").userId(USER).title("Team sync").iCalUID("uid-9")
                .origin(EventOrigin.google("other@gmail.com", "primary"))
                .deleted(false).build();
        when(mappingRepository.findByGoogleEventIdAndUserId("G9", USER)).thenReturn(Optional.empty());
        when(dailyTaskRepository.findByUserAndICalUID(USER, "uid-9")).thenReturn(List.of(live));
        stubSinglePull(event("G9", "confirmed", "uid-9"));

        service.syncCalendar(USER, EMAIL, true);

        // No duplicate task created…
        verify(dailyTaskRepository, never()).save(any());
        // …just a new SYNCED mapping linking this account's copy to the existing task.
        ArgumentCaptor<CalendarSyncMapping> cap = ArgumentCaptor.forClass(CalendarSyncMapping.class);
        verify(mappingRepository).save(cap.capture());
        assertEquals("T1", cap.getValue().getTaskId());
        assertEquals(EMAIL, cap.getValue().getCalendarEmail());
        assertEquals("SYNCED", cap.getValue().getSyncState());
    }

    // ── Baseline: genuinely new event still creates a task ─────────────────────

    @Test
    void unknownEvent_createsNewTaskAndMapping() throws Exception {
        when(mappingRepository.findByGoogleEventIdAndUserId("G3", USER)).thenReturn(Optional.empty());
        when(dailyTaskRepository.findByUserAndICalUID(USER, "uid-new")).thenReturn(List.of());
        when(dailyTaskRepository.save(any(DailyTask.class))).thenAnswer(inv -> {
            DailyTask t = inv.getArgument(0);
            if (t.getId() == null) t.setId("NEW1");
            return t;
        });
        // needs a start node for mapGoogleEventToLocal
        JsonNode ev = mapper.readTree("{\"id\":\"G3\",\"status\":\"confirmed\","
                + "\"updated\":\"2026-07-01T10:00:00.000Z\",\"etag\":\"e\",\"iCalUID\":\"uid-new\","
                + "\"summary\":\"New\",\"start\":{\"date\":\"2026-07-02\"},\"end\":{\"date\":\"2026-07-03\"}}");
        stubSinglePull(ev);

        service.syncCalendar(USER, EMAIL, true);

        ArgumentCaptor<DailyTask> taskCap = ArgumentCaptor.forClass(DailyTask.class);
        verify(dailyTaskRepository).save(taskCap.capture());
        assertEquals("uid-new", taskCap.getValue().getICalUID());
        assertTrue(taskCap.getValue().getOrigin().isGoogle());
        verify(mappingRepository).save(any(CalendarSyncMapping.class));
    }
}
