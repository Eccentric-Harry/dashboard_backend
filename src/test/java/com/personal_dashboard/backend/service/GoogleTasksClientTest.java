package com.personal_dashboard.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.personal_dashboard.backend.model.DailyTask;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class GoogleTasksClientTest {

    @Mock private GoogleApiExecutor executor;

    private GoogleTasksClient client;

    @BeforeEach
    void setUp() {
        client = new GoogleTasksClient(executor, new ObjectMapper());
    }

    private DailyTask task() {
        return DailyTask.builder()
                .id("64f0c0ffee0000000000abcd")
                .title("Book Tickets to Vijayawada")
                .notes("aisle seat")
                .date(LocalDate.of(2026, 9, 24))
                .scheduledTime("13:00")
                .startTime("13:00")
                .completed(false)
                .build();
    }

    @Test
    void buildsTheFourWritableFields() {
        ObjectNode node = client.buildTaskNode(task());
        assertEquals("Book Tickets to Vijayawada", node.get("title").asText());
        assertEquals("aisle seat", node.get("notes").asText());
        assertEquals("needsAction", node.get("status").asText());
        assertTrue(node.has("due"));
    }

    @Test
    void dueIsMidnightUtcOnTheTaskDate() {
        ObjectNode node = client.buildTaskNode(task());
        assertEquals("2026-09-24T00:00:00Z", node.get("due").asText());
    }

    @Test
    void timeOfDayIsNeverSent() {
        // The Tasks API documents that it discards the time portion of `due`. Sending a
        // real time would not round-trip, so time stays a local-only concept.
        ObjectNode node = client.buildTaskNode(task());
        assertFalse(node.get("due").asText().contains("13:00"));
        assertFalse(node.has("scheduledTime"));
        assertFalse(node.has("startTime"));
    }

    @Test
    void serverOwnedFieldsAreNeverSent() {
        // completed/position/parent/hidden are output-only; sending them is rejected or
        // silently ignored, and `completed` is stamped by Google from `status`.
        ObjectNode node = client.buildTaskNode(task());
        assertFalse(node.has("completed"));
        assertFalse(node.has("position"));
        assertFalse(node.has("parent"));
        assertFalse(node.has("hidden"));
        assertFalse(node.has("id"));
    }

    @Test
    void completedTaskMapsToCompletedStatus() {
        DailyTask done = task();
        done.setCompleted(true);
        assertEquals("completed", client.buildTaskNode(done).get("status").asText());
    }

    @Test
    void missingTitleAndNotesDegradeSafely() {
        DailyTask bare = DailyTask.builder().id("x").date(LocalDate.of(2026, 1, 1)).build();
        ObjectNode node = client.buildTaskNode(bare);
        assertEquals("Untitled", node.get("title").asText());
        assertEquals("", node.get("notes").asText());
        assertEquals("needsAction", node.get("status").asText());
    }

    @Test
    void undatedTaskOmitsDueRatherThanInventingOne() {
        DailyTask undated = DailyTask.builder().id("x").title("someday").build();
        assertFalse(client.buildTaskNode(undated).has("due"));
    }
}
