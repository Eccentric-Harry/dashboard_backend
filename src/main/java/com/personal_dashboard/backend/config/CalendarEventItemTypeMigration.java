package com.personal_dashboard.backend.config;

import com.personal_dashboard.backend.model.DailyTask;
import com.personal_dashboard.backend.model.EventOrigin;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

/**
 * Repairs {@code daily_tasks} rows that were imported from Google <em>Calendar</em>
 * but stored as {@code itemType: "TASK"}.
 *
 * <p>Until this was fixed, every event pulled from Google Calendar was written as a
 * TASK, so the /tasks route filled up with calendar entries — lunches, flights,
 * evening walks, birthdays — that were never to-dos. The forward fix lives in
 * {@code GoogleSyncService.processGoogleEvents()}, which now mints EVENT. This
 * migration retypes the rows that the old code already wrote.
 *
 * <p>Two populations are retyped, and nothing else:
 * <ol>
 *   <li><b>{@code origin.source == "GOOGLE"}</b> — unambiguous: that marker is only
 *       ever set by the Google <em>Calendar</em> pull. Google <em>Tasks</em> imports
 *       carry {@code GOOGLE_TASKS} and are deliberately excluded.</li>
 *   <li><b>No {@code origin} field at all</b> — the pre-identity-model import batch.
 *       These predate {@link EventOrigin} entirely, so they can only have come from
 *       that one bulk Calendar import.</li>
 * </ol>
 *
 * <p>Locally-created tasks are never touched: they carry {@code origin.source == "LOCAL"}.
 * Having a {@code calendar_sync_mappings} row is deliberately <em>not</em> part of the
 * predicate — local tasks get one too as soon as they are pushed out to Google, so it
 * would sweep up genuine to-dos.
 *
 * <p>Idempotent: re-running matches nothing once the rows are EVENT. Fail-soft, like
 * {@link MongoIndexInitializer} — a migration must never take startup down.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CalendarEventItemTypeMigration {

    private final MongoTemplate mongoTemplate;

    @EventListener(ApplicationReadyEvent.class)
    public void retypeCalendarImports() {
        try {
            long pulled = retype(
                    Criteria.where("itemType").is("TASK").and("origin.source").is(EventOrigin.SOURCE_GOOGLE),
                    "origin.source=GOOGLE");

            long legacy = retype(
                    Criteria.where("itemType").is("TASK").and("origin").exists(false),
                    "legacy import (no origin)");

            if (pulled + legacy > 0) {
                log.info("Calendar-import migration: retyped {} row(s) from TASK to EVENT ({} tagged GOOGLE, {} legacy). "
                        + "These are Google Calendar entries and no longer appear on the /tasks route.",
                        pulled + legacy, pulled, legacy);
            } else {
                log.debug("Calendar-import migration: nothing to retype.");
            }
        } catch (RuntimeException e) {
            log.warn("Calendar-import migration failed — continuing startup without it: {}", e.getMessage());
        }
    }

    private long retype(Criteria criteria, String label) {
        long matched = mongoTemplate.count(new Query(criteria), DailyTask.class);
        if (matched == 0) {
            return 0;
        }
        log.info("Calendar-import migration: retyping {} row(s) matching {} → itemType=EVENT", matched, label);
        return mongoTemplate.updateMulti(new Query(criteria), new Update().set("itemType", "EVENT"), DailyTask.class)
                .getModifiedCount();
    }
}
