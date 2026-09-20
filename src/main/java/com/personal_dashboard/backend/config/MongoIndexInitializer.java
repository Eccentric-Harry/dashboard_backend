package com.personal_dashboard.backend.config;

import com.personal_dashboard.backend.model.AuthToken;
import com.personal_dashboard.backend.model.CalendarSyncMapping;
import com.personal_dashboard.backend.model.DailyTask;
import com.personal_dashboard.backend.model.MindEntry;
import com.personal_dashboard.backend.model.SyncOutboxEntry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Creates the indexes the hot paths depend on. Spring Data's auto-index-creation is off
 * (its default), so model annotations alone create nothing — these are ensured explicitly.
 *
 * <p>This is the <em>only</em> place indexes come from. The {@code @Indexed} and
 * {@code @CompoundIndex} annotations on the models are documentation, not instructions:
 * without {@code spring.data.mongodb.auto-index-creation=true} Spring Data never acts on
 * them, so anything a hot path needs has to be repeated here. That flag is deliberately
 * left off — several models declare <em>unique</em> compound indexes, and creating one of
 * those over a collection that already holds a duplicate fails the build and takes the
 * whole application down with it. Every index below is non-unique for the same reason.
 *
 * <ul>
 *   <li>{@code auth_tokens.token} — looked up by every request whose token isn't cached.</li>
 *   <li>{@code auth_tokens.expiresAt} TTL — Mongo purges tokens once they expire, so
 *       dead credentials don't accumulate (a login mints a new token each time).</li>
 *   <li>{@code daily_tasks (userId, date)} — the calendar/tasks/summary range reads.</li>
 *   <li>{@code daily_tasks (userId, iCalUID)} and {@code (userId, googleEventId)} — looked
 *       up <em>once per event</em> by a Google sync. Unindexed these turn one sync into a
 *       collection scan per event, which is enough to saturate a small instance and make
 *       unrelated reads time out while it runs.</li>
 *   <li>{@code daily_tasks (userId, completed, date)} — findActiveTasks, behind /dashboard
 *       and the tasks route.</li>
 *   <li>{@code calendar_sync_mappings (googleEventId, userId)} — the other per-event lookup
 *       in the same sync loop.</li>
 *   <li>{@code sync_outbox (status, nextAttemptAt)} — scanned by OutboxDrainJob every 15s.</li>
 *   <li>{@code mind_entries (userId, date)} — every /mind read.</li>
 * </ul>
 *
 * Creation is idempotent and fail-soft: an existing index with a conflicting definition
 * is logged and left alone rather than blocking startup.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MongoIndexInitializer {

    private final MongoTemplate mongoTemplate;

    @EventListener(ApplicationReadyEvent.class)
    public void ensureIndexes() {
        ensure(AuthToken.class, new Index().on("token", Sort.Direction.ASC).unique().named("token_unique"));
        ensure(AuthToken.class, new Index().on("expiresAt", Sort.Direction.ASC).expire(Duration.ZERO).named("expiresAt_ttl"));
        ensure(DailyTask.class, new Index().on("userId", Sort.Direction.ASC).on("date", Sort.Direction.ASC).named("user_date_idx"));
        ensure(DailyTask.class, new Index().on("userId", Sort.Direction.ASC).on("iCalUID", Sort.Direction.ASC).named("user_icaluid_idx"));
        ensure(DailyTask.class, new Index().on("userId", Sort.Direction.ASC).on("googleEventId", Sort.Direction.ASC).named("user_google_event_idx"));
        ensure(DailyTask.class, new Index().on("userId", Sort.Direction.ASC).on("completed", Sort.Direction.ASC).on("date", Sort.Direction.ASC).named("user_completed_date_idx"));
        ensure(CalendarSyncMapping.class, new Index().on("googleEventId", Sort.Direction.ASC).on("userId", Sort.Direction.ASC).named("google_event_user_idx"));
        ensure(SyncOutboxEntry.class, new Index().on("status", Sort.Direction.ASC).on("nextAttemptAt", Sort.Direction.ASC).named("status_next_attempt_idx"));
        ensure(MindEntry.class, new Index().on("userId", Sort.Direction.ASC).on("date", Sort.Direction.ASC).named("user_date_idx"));
    }

    private void ensure(Class<?> entity, Index index) {
        try {
            String name = mongoTemplate.indexOps(entity).createIndex(index);
            log.info("Ensured index {} on {}", name, mongoTemplate.getCollectionName(entity));
        } catch (RuntimeException e) {
            log.warn("Could not ensure index {} on {} — continuing without it: {}",
                    index.getIndexOptions().get("name"), mongoTemplate.getCollectionName(entity), e.getMessage());
        }
    }
}
