package com.personal_dashboard.backend.config;

import com.personal_dashboard.backend.model.AuthToken;
import com.personal_dashboard.backend.model.CalendarSyncMapping;
import com.personal_dashboard.backend.model.DailyTask;
import com.personal_dashboard.backend.model.Goal;
import com.personal_dashboard.backend.model.GoalCamp;
import com.personal_dashboard.backend.model.GoalCheckIn;
import com.personal_dashboard.backend.model.GoalKit;
import com.personal_dashboard.backend.model.GoogleTaskListMapping;
import com.personal_dashboard.backend.model.MindEntry;
import com.personal_dashboard.backend.model.Program;
import com.personal_dashboard.backend.model.ProgramAssessment;
import com.personal_dashboard.backend.model.ProgramLog;
import com.personal_dashboard.backend.model.ProgramMedia;
import com.personal_dashboard.backend.model.ProgramReview;
import com.personal_dashboard.backend.model.SavingsGoal;
import com.personal_dashboard.backend.model.ShoppingItem;
import com.personal_dashboard.backend.model.ShoppingMemory;
import com.personal_dashboard.backend.model.WishlistItem;
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
 *   <li>{@code goals (userId, status)} and {@code goal_checkins (userId, goalId, date)} — the
 *       /goals board; its all-check-ins read uses the {@code userId} prefix.</li>
 *   <li>{@code goal_camps.userId} <em>unique</em> — one camp per user. GoalCampService creates
 *       it with an upsert on first write; the uniqueness is what settles two first writes racing.</li>
 *   <li>{@code goal_kits (userId, goalId)} <em>unique</em> — one kit per goal world, created by
 *       GoalKitService's first page upsert; the uniqueness settles two first saves racing.</li>
 *   <li>{@code programs (userId, status)}, {@code program_logs / program_assessments /
 *       program_media (userId, programId, …)} — The Lighthouse's one read per visit.
 *       {@code program_reviews (userId, programId, weekStart)} <em>unique</em> — one review a
 *       week; the uniqueness settles two saves of a new week's review racing.</li>
 *   <li>{@code savings_goals.userId} — every /finance load lists the user's goals.</li>
 *   <li>{@code shopping_items.userId} — every /shopping load reads the user's whole list.</li>
 *   <li>{@code shopping_memory.userId} <em>unique</em> — one memory per user, created by the first add.</li>
 *   <li>{@code wishlist_items.userId} — every Wishlist load reads the user's wishes.</li>
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
        ensure(Goal.class, new Index().on("userId", Sort.Direction.ASC).on("status", Sort.Direction.ASC).named("user_status_idx"));
        ensure(GoalCamp.class, new Index().on("userId", Sort.Direction.ASC).unique().named("user_unique"));
        ensure(GoalKit.class, new Index().on("userId", Sort.Direction.ASC).on("goalId", Sort.Direction.ASC).unique().named("user_goal_unique"));
        ensure(GoalCheckIn.class, new Index().on("userId", Sort.Direction.ASC).on("goalId", Sort.Direction.ASC).on("date", Sort.Direction.ASC).named("user_goal_date_idx"));
        ensure(Program.class, new Index().on("userId", Sort.Direction.ASC).on("status", Sort.Direction.ASC).named("user_status_idx"));
        ensure(ProgramLog.class, new Index().on("userId", Sort.Direction.ASC).on("programId", Sort.Direction.ASC).on("date", Sort.Direction.ASC).named("user_program_date_idx"));
        ensure(ProgramReview.class, new Index().on("userId", Sort.Direction.ASC).on("programId", Sort.Direction.ASC).on("weekStart", Sort.Direction.ASC).unique().named("user_program_week_unique"));
        ensure(ProgramAssessment.class, new Index().on("userId", Sort.Direction.ASC).on("programId", Sort.Direction.ASC).on("date", Sort.Direction.ASC).named("user_program_date_idx"));
        ensure(ProgramMedia.class, new Index().on("userId", Sort.Direction.ASC).on("programId", Sort.Direction.ASC).on("date", Sort.Direction.ASC).named("user_program_date_idx"));
        ensure(SavingsGoal.class, new Index().on("userId", Sort.Direction.ASC).named("user_idx"));
        ensure(ShoppingItem.class, new Index().on("userId", Sort.Direction.ASC).named("user_idx"));
        ensure(ShoppingMemory.class, new Index().on("userId", Sort.Direction.ASC).unique().named("user_unique"));
        ensure(WishlistItem.class, new Index().on("userId", Sort.Direction.ASC).named("user_idx"));
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
