package com.personal_dashboard.backend.service;

import com.personal_dashboard.backend.model.SyncOutboxEntry;
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
class OutboxServiceTest {

    @Mock private SyncOutboxRepository outboxRepository;
    @InjectMocks private OutboxService service;

    @Test
    void enqueue_createsPendingEntry() {
        when(outboxRepository.findFirstByTaskIdAndStatus("T1", "PENDING")).thenReturn(Optional.empty());

        service.enqueue("T1");

        ArgumentCaptor<SyncOutboxEntry> cap = ArgumentCaptor.forClass(SyncOutboxEntry.class);
        verify(outboxRepository).save(cap.capture());
        assertEquals("T1", cap.getValue().getTaskId());
        assertEquals("PENDING", cap.getValue().getStatus());
        assertNotNull(cap.getValue().getNextAttemptAt());
    }

    @Test
    void enqueue_coalescesOntoExistingPending() {
        SyncOutboxEntry existing = SyncOutboxEntry.builder()
                .id("O1").taskId("T1").status("PENDING").attempts(3).build();
        when(outboxRepository.findFirstByTaskIdAndStatus("T1", "PENDING")).thenReturn(Optional.of(existing));

        service.enqueue("T1");

        // Same entry reused (not a new one) and attempts reset for the fresh change.
        verify(outboxRepository).save(existing);
        assertEquals(0, existing.getAttempts());
        assertEquals("O1", existing.getId());
    }

    @Test
    void enqueue_ignoresBlankTaskId() {
        service.enqueue("  ");
        verify(outboxRepository, never()).save(any());
    }
}
