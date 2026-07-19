package com.personal_dashboard.backend.service;

import com.personal_dashboard.backend.model.SyncOutboxEntry;
import com.personal_dashboard.backend.repository.SyncOutboxRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Optional;

/**
 * Enqueues durable outbox entries for outbound pushes. Coalesces repeated changes
 * to the same task into a single PENDING entry so a burst of edits drains once.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OutboxService {

    private final SyncOutboxRepository outboxRepository;

    public void enqueue(String taskId) {
        if (taskId == null || taskId.isBlank()) return;
        Instant now = Instant.now();

        Optional<SyncOutboxEntry> existing = outboxRepository.findFirstByTaskIdAndStatus(taskId, "PENDING");
        SyncOutboxEntry entry = existing.orElseGet(() -> SyncOutboxEntry.builder()
                .taskId(taskId)
                .status("PENDING")
                .createdAt(now)
                .build());
        // (Re)arm for immediate processing; reset attempts on a fresh change.
        entry.setStatus("PENDING");
        entry.setAttempts(0);
        entry.setNextAttemptAt(now);
        entry.setUpdatedAt(now);
        outboxRepository.save(entry);
        log.debug("Enqueued outbox entry for task {} ({})", taskId,
                existing.isPresent() ? "coalesced into existing pending entry" : "new entry");
    }
}
