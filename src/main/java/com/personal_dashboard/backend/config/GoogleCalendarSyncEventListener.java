package com.personal_dashboard.backend.config;

import com.personal_dashboard.backend.model.DailyTask;
import com.personal_dashboard.backend.service.GoogleSyncContext;
import com.personal_dashboard.backend.service.OutboxService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.springframework.data.mongodb.core.mapping.event.AbstractMongoEventListener;
import org.springframework.data.mongodb.core.mapping.event.AfterSaveEvent;
import org.springframework.data.mongodb.core.mapping.event.BeforeDeleteEvent;
import org.springframework.stereotype.Component;

/**
 * Mongo event listener that mirrors DailyTask changes to Google Calendar.
 *
 * It no longer pushes inline (fire-and-forget). Instead it enqueues a durable
 * outbox entry; the OutboxDrainJob worker performs the actual push with retries,
 * so a crash during the remote call cannot lose the change. The per-account push
 * itself lives in OutboundPusher.
 *
 * Note: the enqueue happens post-commit (after the task save), so there is a
 * small non-transactional window between the save and the enqueue. Closing it
 * fully means writing the outbox entry in the same transaction as the task save
 * (a MongoTransactionManager + service-layer @Transactional writer) — deferred
 * until it can be validated against the Atlas replica set.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class GoogleCalendarSyncEventListener extends AbstractMongoEventListener<DailyTask> {

    private final OutboxService outboxService;

    @Override
    public void onAfterSave(AfterSaveEvent<DailyTask> event) {
        if (GoogleSyncContext.isBypass()) {
            // Save originated from inbound sync or an outbound bookkeeping write — don't echo it.
            return;
        }
        DailyTask task = event.getSource();
        if (task.getUserId() == null || task.getUserId().isBlank()) return;
        outboxService.enqueue(task.getId());
    }

    @Override
    public void onBeforeDelete(BeforeDeleteEvent<DailyTask> event) {
        if (GoogleSyncContext.isBypass()) return;
        // Hard deletes no longer occur (deletes are soft), but enqueue defensively so a
        // stray hard delete still triggers reconciliation of any linked remotes.
        Document queryDoc = event.getSource();
        if (queryDoc == null || !queryDoc.containsKey("_id")) return;
        outboxService.enqueue(queryDoc.get("_id").toString());
    }
}
