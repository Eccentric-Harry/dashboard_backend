package com.personal_dashboard.backend.config;

import com.personal_dashboard.backend.model.AuthToken;
import com.personal_dashboard.backend.model.CalendarSyncMapping;
import com.personal_dashboard.backend.model.DailyTask;
import com.personal_dashboard.backend.model.GoogleTaskListMapping;
import com.personal_dashboard.backend.model.MindEntry;
import com.personal_dashboard.backend.model.PushSubscription;
import com.personal_dashboard.backend.model.ScheduledNotification;
import com.personal_dashboard.backend.model.SyncOutboxEntry;
import com.personal_dashboard.backend.model.TaskSyncMapping;
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
 * whole application down with it. The two unique indexes below ({@code auth_tokens.token} and
 * {@code push_subscriptions.endpoint}) are deliberate exceptions: both are over values that are
 * unique by construction, and a duplicate there is a correctness bug worth surfacing. Creation
 * stays fail-soft, so even then startup is not blocked.
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
 *   <li>{@code task_sync_mappings (googleTaskId, userId)} — resolved <em>once per remote task</em>
 *       by every Google Tasks poll. Unindexed, a poll degrades into one collection scan per
 *       task returned, which is the same failure mode the calendar mapping index prevents.</li>
 *   <li>{@code task_sync_mappings (userId, accountEmail)} — the status/diagnostics counts.</li>
 *   <li>{@code google_task_lists (userId, accountEmail)} — read at the top of every poll.</li>
 *   <li>{@code mind_entries (userId, date)} — every /mind read.</li>
 *   <li>{@code push_subscriptions.endpoint} <em>unique</em> — an endpoint identifies one device,
 *       so this is what stops two tabs subscribing at once from creating two rows and pushing
 *       the same alert twice. The uniqueness is the mechanism, not a nicety.</li>
 *   <li>{@code push_subscriptions (userId, active)} — read for every dispatch.</li>
 *   <li>{@code scheduled_notifications (status, fireAt, nextAttemptAt)} — the dispatcher's claim
 *       query, which runs every 15s.</li>
 *   <li>{@code scheduled_notifications (userId, fireAt)} — the notification-centre feed.</li>
 *   <li>{@code scheduled_notifications.actionToken} — the service worker's snooze lookup.</li>
 *   <li>{@code scheduled_notifications.expiresAt} TTL — records self-purge, so the collection
 *       cannot grow without bound the way the old per-push log did.</li>
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
        ensure(TaskSyncMapping.class, new Index().on("googleTaskId", Sort.Direction.ASC).on("userId", Sort.Direction.ASC).named("google_task_user_idx"));
        ensure(TaskSyncMapping.class, new Index().on("userId", Sort.Direction.ASC).on("accountEmail", Sort.Direction.ASC).named("user_account_idx"));
        ensure(GoogleTaskListMapping.class, new Index().on("userId", Sort.Direction.ASC).on("accountEmail", Sort.Direction.ASC).named("user_account_idx"));
        ensure(MindEntry.class, new Index().on("userId", Sort.Direction.ASC).on("date", Sort.Direction.ASC).named("user_date_idx"));
        ensure(PushSubscription.class, new Index().on("endpoint", Sort.Direction.ASC).unique().named("endpoint_unique"));
        ensure(PushSubscription.class, new Index().on("userId", Sort.Direction.ASC).on("active", Sort.Direction.ASC).named("user_active_idx"));
        ensure(ScheduledNotification.class, new Index().on("status", Sort.Direction.ASC).on("fireAt", Sort.Direction.ASC).on("nextAttemptAt", Sort.Direction.ASC).named("status_fire_attempt_idx"));
        ensure(ScheduledNotification.class, new Index().on("userId", Sort.Direction.ASC).on("fireAt", Sort.Direction.DESC).named("user_fire_idx"));
        ensure(ScheduledNotification.class, new Index().on("actionToken", Sort.Direction.ASC).sparse().named("action_token_idx"));
        ensure(ScheduledNotification.class, new Index().on("expiresAt", Sort.Direction.ASC).expire(Duration.ZERO).named("expiresAt_ttl"));
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
