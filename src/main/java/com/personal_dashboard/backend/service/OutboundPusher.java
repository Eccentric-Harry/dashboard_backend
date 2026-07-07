package com.personal_dashboard.backend.service;

import com.personal_dashboard.backend.model.CalendarSyncMapping;
import com.personal_dashboard.backend.model.DailyTask;
import com.personal_dashboard.backend.model.GoogleSyncStore;
import com.personal_dashboard.backend.repository.CalendarSyncMappingRepository;
import com.personal_dashboard.backend.repository.DailyTaskRepository;
import com.personal_dashboard.backend.repository.GoogleSyncStoreRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Performs the actual outbound push of one local DailyTask to every connected
 * Google account. Synchronous and idempotent — safe to re-run (the outbox worker
 * may call it more than once): insert/update is decided by mapping existence, and
 * a re-insert uses a deterministic id so a repeat cannot duplicate.
 *
 * This is the push logic that used to live inline in GoogleCalendarSyncEventListener;
 * it is now driven by the durable outbox worker rather than fire-and-forget.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OutboundPusher {

    private final GoogleCalendarClient googleCalendarClient;
    private final GoogleSyncStoreRepository syncStoreRepository;
    private final DailyTaskRepository dailyTaskRepository;
    private final CalendarSyncMappingRepository mappingRepository;
    private final CalendarSyncLocks syncLocks;

    /** Reconcile a task against all of its owner's connected Google accounts. */
    public void reconcile(DailyTask task) throws Exception {
        String userId = task.getUserId();
        if (userId == null || userId.isBlank()) return;

        // Recurring tasks ARE pushed outbound as native Google recurring events
        // (RRULE emitted by GoogleCalendarClient.buildEventNode). Inbound recurrence
        // (pulling Google recurring series/instances) is still deferred.

        List<GoogleSyncStore> stores = syncStoreRepository.findByUserId(userId);
        for (GoogleSyncStore store : stores) {
            pushToStore(task, store);
        }
    }

    /** Push a single task to a single account, holding the per-calendar lock. */
    public void pushToStore(DailyTask task, GoogleSyncStore store) throws Exception {
        if (store.isDisconnected()) {
            return; // skip accounts needing reconnect
        }
        String userId = task.getUserId();
        String calendarEmail = store.getEmail();
        String storeId = store.getId();
        String mappingId = CalendarSyncMapping.compositeId(task.getId(), calendarEmail);

        ReentrantLock lock = syncLocks.forStore(storeId);
        lock.lock();
        try {
            Optional<CalendarSyncMapping> existingMapping = mappingRepository.findById(mappingId);

            // Origin gate (guardrail #4 + cross-account #10). Soft-deletes are exempt —
            // they only cancel an existing mapping, never create a new remote.
            if (!Boolean.TRUE.equals(task.getDeleted()) && !SyncPushPolicy.shouldPush(task, store)) {
                log.debug("Outbound: Skipping push of task {} to {} (origin gate).", task.getId(), calendarEmail);
                return;
            }

            if (Boolean.TRUE.equals(task.getDeleted())) {
                if (existingMapping.isPresent()) {
                    CalendarSyncMapping mapping = existingMapping.get();
                    log.info("Outbound: Cancelling Google Event {} for soft-deleted task '{}' ({})",
                            mapping.getGoogleEventId(), task.getTitle(), calendarEmail);
                    googleCalendarClient.deleteEvent(storeId, mapping.getGoogleEventId());
                    saveBypassing(() -> {
                        mapping.setSyncState("CANCELLED");
                        mapping.setLastSyncedAt(Instant.now());
                        mappingRepository.save(mapping);
                    });
                }
                return;
            }

            if (existingMapping.isEmpty()) {
                log.info("Outbound: Inserting task '{}' into Google Calendar ({})", task.getTitle(), calendarEmail);
                String googleEventId = googleCalendarClient.insertEvent(storeId, task);
                CalendarSyncMapping mapping = CalendarSyncMapping.builder()
                        .id(mappingId)
                        .taskId(task.getId())
                        .userId(userId)
                        .calendarEmail(calendarEmail)
                        .googleEventId(googleEventId)
                        .lastSyncedAt(Instant.now())
                        .build();
                saveBypassing(() -> {
                    mappingRepository.save(mapping);
                    task.setLastSyncedAt(Instant.now());
                    dailyTaskRepository.save(task);
                });
                log.info("Outbound: Inserted Google Event ID {} ({})", googleEventId, calendarEmail);
            } else {
                CalendarSyncMapping mapping = existingMapping.get();
                GoogleCalendarClient.UpdateResult result =
                        googleCalendarClient.updateEvent(storeId, mapping.getGoogleEventId(), task, mapping.getEtag());
                saveBypassing(() -> {
                    mapping.setGoogleEventId(result.googleEventId());
                    if (result.etag() != null) mapping.setEtag(result.etag());
                    mapping.setLastSyncedAt(Instant.now());
                    mappingRepository.save(mapping);
                    task.setLastSyncedAt(Instant.now());
                    dailyTaskRepository.save(task);
                });
                if (result.applied()) {
                    log.info("Outbound: Updated Google Event successfully ({})", calendarEmail);
                } else {
                    log.info("Outbound: Remote won last-write-wins for Google Event {} ({}) — will reconcile on next pull.",
                            mapping.getGoogleEventId(), calendarEmail);
                }
            }
        } finally {
            lock.unlock();
        }
    }

    /** Run a save under the sync-context bypass so it doesn't re-enqueue itself. */
    private void saveBypassing(Runnable work) {
        GoogleSyncContext.setBypass(true);
        try {
            work.run();
        } finally {
            GoogleSyncContext.clear();
        }
    }
}
