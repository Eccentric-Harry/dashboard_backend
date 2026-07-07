package com.personal_dashboard.backend.service;

import com.personal_dashboard.backend.model.DailyTask;
import com.personal_dashboard.backend.model.SyncOutboxEntry;
import com.personal_dashboard.backend.repository.DailyTaskRepository;
import com.personal_dashboard.backend.repository.SyncOutboxRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OutboxDrainJobTest {

    @Mock private SyncOutboxRepository outboxRepository;
    @Mock private DailyTaskRepository dailyTaskRepository;
    @Mock private OutboundPusher outboundPusher;

    @InjectMocks private OutboxDrainJob job;

    private SyncOutboxEntry entry(int attempts) {
        return SyncOutboxEntry.builder().id("O1").taskId("T1").status("PENDING").attempts(attempts).build();
    }

    @Test
    void success_marksDone() throws Exception {
        DailyTask task = DailyTask.builder().id("T1").userId("u").build();
        when(dailyTaskRepository.findById("T1")).thenReturn(Optional.of(task));
        SyncOutboxEntry e = entry(0);

        job.processEntry(e);

        verify(outboundPusher).reconcile(task);
        ArgumentCaptor<SyncOutboxEntry> cap = ArgumentCaptor.forClass(SyncOutboxEntry.class);
        verify(outboxRepository).save(cap.capture());
        assertEquals("DONE", cap.getValue().getStatus());
    }

    @Test
    void failure_retriesWithBackoff_staysPending() throws Exception {
        DailyTask task = DailyTask.builder().id("T1").userId("u").build();
        when(dailyTaskRepository.findById("T1")).thenReturn(Optional.of(task));
        doThrow(new RuntimeException("boom")).when(outboundPusher).reconcile(task);
        SyncOutboxEntry e = entry(0);

        job.processEntry(e);

        assertEquals("PENDING", e.getStatus());
        assertEquals(1, e.getAttempts());
        assertNotNull(e.getNextAttemptAt());
        assertEquals("boom", e.getLastError());
    }

    @Test
    void failure_exhaustsAttempts_marksFailed() throws Exception {
        DailyTask task = DailyTask.builder().id("T1").userId("u").build();
        when(dailyTaskRepository.findById("T1")).thenReturn(Optional.of(task));
        doThrow(new RuntimeException("boom")).when(outboundPusher).reconcile(task);
        SyncOutboxEntry e = entry(7); // MAX_ATTEMPTS = 8

        job.processEntry(e);

        assertEquals("FAILED", e.getStatus());
        assertEquals(8, e.getAttempts());
    }

    @Test
    void missingTask_marksDone() throws Exception {
        when(dailyTaskRepository.findById("T1")).thenReturn(Optional.empty());
        SyncOutboxEntry e = entry(0);

        job.processEntry(e);

        verify(outboundPusher, never()).reconcile(any());
        assertEquals("DONE", e.getStatus());
    }

    @Test
    void backoff_isBoundedAndCapped() {
        for (int attempt = 1; attempt <= 20; attempt++) {
            long b = OutboxDrainJob.backoffMillis(attempt);
            assertTrue(b >= 0 && b <= 600_000L, "attempt " + attempt + " -> " + b);
        }
    }
}
