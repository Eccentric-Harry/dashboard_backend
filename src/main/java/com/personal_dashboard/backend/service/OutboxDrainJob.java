package com.personal_dashboard.backend.service;

import com.personal_dashboard.backend.model.DailyTask;
import com.personal_dashboard.backend.model.SyncOutboxEntry;
import com.personal_dashboard.backend.repository.DailyTaskRepository;
import com.personal_dashboard.backend.repository.SyncOutboxRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Drains the sync outbox: loads each PENDING task, reconciles it against Google
 * (idempotently), and marks it DONE. Failures are retried with exponential
 * backoff + jitter and marked FAILED after MAX_ATTEMPTS. Resumable — a crash
 * mid-drain simply leaves entries PENDING for the next tick.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OutboxDrainJob {

    private static final int MAX_ATTEMPTS = 8;

    private final SyncOutboxRepository outboxRepository;
    private final DailyTaskRepository dailyTaskRepository;
    private final OutboundPusher outboundPusher;

    @Scheduled(fixedDelayString = "${sync.outbox.drain-interval-ms:15000}")
    public void drain() {
        List<SyncOutboxEntry> due =
                outboxRepository.findByStatusAndNextAttemptAtLessThanEqual("PENDING", Instant.now());
        if (due.isEmpty()) return;

        log.debug("Outbox drain: {} due entr(ies)", due.size());
        for (SyncOutboxEntry entry : due) {
            processEntry(entry);
        }
    }

    void processEntry(SyncOutboxEntry entry) {
        try {
            DailyTask task = dailyTaskRepository.findById(entry.getTaskId()).orElse(null);
            if (task == null) {
                // Task no longer exists (should not happen under soft-delete) — done.
                markDone(entry);
                return;
            }
            outboundPusher.reconcile(task);
            markDone(entry);
        } catch (Exception e) {
            handleFailure(entry, e);
        }
    }

    private void markDone(SyncOutboxEntry entry) {
        entry.setStatus("DONE");
        entry.setUpdatedAt(Instant.now());
        outboxRepository.save(entry);
    }

    private void handleFailure(SyncOutboxEntry entry, Exception e) {
        int attempts = entry.getAttempts() + 1;
        entry.setAttempts(attempts);
        entry.setLastError(e.getMessage());
        entry.setUpdatedAt(Instant.now());
        if (attempts >= MAX_ATTEMPTS) {
            entry.setStatus("FAILED");
            log.error("Outbox entry {} (task {}) FAILED after {} attempts: {}",
                    entry.getId(), entry.getTaskId(), attempts, e.getMessage());
        } else {
            long backoff = backoffMillis(attempts);
            entry.setNextAttemptAt(Instant.now().plus(backoff, ChronoUnit.MILLIS));
            log.warn("Outbox entry {} (task {}) attempt {} failed: {}; retrying in {}ms",
                    entry.getId(), entry.getTaskId(), attempts, e.getMessage(), backoff);
        }
        outboxRepository.save(entry);
    }

    /** Exponential backoff with full jitter, capped at 10 minutes. */
    static long backoffMillis(int attempt) {
        long base = 1_000L << Math.min(attempt - 1, 9);
        long capped = Math.min(base, 600_000L);
        return ThreadLocalRandom.current().nextLong(capped + 1);
    }
}
