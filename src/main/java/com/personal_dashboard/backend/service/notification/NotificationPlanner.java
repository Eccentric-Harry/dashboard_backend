package com.personal_dashboard.backend.service.notification;

import com.personal_dashboard.backend.model.DailyTask;
import com.personal_dashboard.backend.model.NotificationStatus;
import com.personal_dashboard.backend.model.PushSubscription;
import com.personal_dashboard.backend.model.ScheduledNotification;
import com.personal_dashboard.backend.repository.DailyTaskRepository;
import com.personal_dashboard.backend.repository.PushSubscriptionRepository;
import com.personal_dashboard.backend.repository.ScheduledNotificationRepository;
import com.personal_dashboard.backend.repository.UserAccountRepository;
import com.personal_dashboard.backend.util.OccurrenceDates;
import com.personal_dashboard.backend.util.TimeZoneUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Turns calendar occurrences into durable {@link ScheduledNotification} rows for a rolling
 * horizon, and keeps those rows honest as the underlying items change.
 *
 * <p>Two rules make the whole system safe:
 *
 * <ol>
 *   <li><b>Nothing is ever planned in the past.</b> An occurrence whose instant has
 *       already gone by is not materialised at all, so no amount of re-planning — on a
 *       restart, on a calendar edit, on every minute of the day — can produce a
 *       notification for a time that has passed.</li>
 *   <li><b>Rows are addressed deterministically.</b> The id is derived from
 *       (user, source, occurrence date, kind), so planning the same occurrence twice is
 *       a rejected insert rather than a second notification.</li>
 * </ol>
 *
 * <p>Wall-clock time is resolved to an absolute instant once, here, using the item's own
 * zone when it has one and the user's profile zone otherwise. The dispatcher then only
 * ever compares instants, so a device in another timezone — or a device that has moved —
 * cannot shift when an event fires.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationPlanner {

    private static final Set<String> NOTIFIABLE_TYPES = Set.of("TASK", "EVENT", "REMINDER", "MILESTONE");

    /**
     * Rows due within this window are the dispatcher's, not the planner's. The planner only
     * ever plans the future, so a row that has just come due would otherwise look "no longer
     * planned" and be cancelled out from under the dispatcher moments before it fires. The
     * dispatcher re-verifies the source anyway, so a deletion inside this window is still honoured.
     */
    private static final Duration CANCEL_GRACE = Duration.ofMinutes(2);

    private final DailyTaskRepository dailyTaskRepository;
    private final PushSubscriptionRepository pushSubscriptionRepository;
    private final ScheduledNotificationRepository notificationRepository;
    private final UserAccountRepository userAccountRepository;

    @Value("${notifications.enabled:true}")
    private boolean enabled;

    /** How far ahead rows are materialised. Long enough to survive a short outage, short enough that edits still land. */
    @Value("${notifications.planning-horizon-minutes:180}")
    private long planningHorizonMinutes;

    /** Local hour an all-day item announces itself. */
    @Value("${notifications.all-day-hour:9}")
    private int allDayHour;

    /** Used when neither the item nor the user profile names a zone. */
    @Value("${notifications.default-timezone:Asia/Kolkata}")
    private String defaultTimezone;

    /** Rows are kept this long after firing so the notification centre has history. */
    @Value("${notifications.retention-days:30}")
    private long retentionDays;

    @Scheduled(fixedDelayString = "${notifications.planner-interval-ms:60000}",
            initialDelayString = "${notifications.planner-initial-delay-ms:20000}")
    public void plan() {
        if (!enabled) {
            return;
        }
        try {
            planAllUsers(Instant.now());
        } catch (RuntimeException e) {
            // A planner tick must never kill the scheduler thread.
            log.error("Notification planning cycle failed", e);
        }
    }

    /** Visible for testing and for the on-demand re-plan after a calendar write. */
    public void planAllUsers(Instant now) {
        List<PushSubscription> active = pushSubscriptionRepository.findByActiveTrue();
        if (active.isEmpty()) {
            return;
        }

        // One pass per user, not per device: two phones must not mean two plans (or two queries).
        Map<String, List<PushSubscription>> byUser = new HashMap<>();
        for (PushSubscription sub : active) {
            if (sub.getUserId() == null) continue;
            byUser.computeIfAbsent(sub.getUserId(), k -> new ArrayList<>()).add(sub);
        }

        for (Map.Entry<String, List<PushSubscription>> entry : byUser.entrySet()) {
            try {
                planForUser(entry.getKey(), entry.getValue(), now);
            } catch (RuntimeException e) {
                log.error("Notification planning failed for userId={}", entry.getKey(), e);
            }
        }
    }

    void planForUser(String userId, List<PushSubscription> subscriptions, Instant now) {
        ZoneId userZone = resolveUserZone(userId, subscriptions);
        Instant windowEnd = now.plus(Duration.ofMinutes(planningHorizonMinutes));

        // Local dates are only used to narrow the Mongo read; every decision below is on instants.
        LocalDate fromDate = now.atZone(userZone).toLocalDate().minusDays(1);
        LocalDate toDate = windowEnd.atZone(userZone).toLocalDate().plusDays(1);

        List<DailyTask> candidates = dailyTaskRepository.findCalendarCandidates(userId, fromDate, toDate.plusDays(1));

        Set<String> plannedIds = new LinkedHashSet<>();
        for (DailyTask item : candidates) {
            try {
                plannedIds.addAll(planItem(userId, item, userZone, now, windowEnd, fromDate, toDate));
            } catch (RuntimeException e) {
                // One malformed item must not stop the rest of this user's plan.
                log.warn("Skipping notification planning for item {} (userId={}): {}",
                        item.getId(), userId, e.getMessage());
            }
        }

        cancelStaleRows(userId, now.plus(CANCEL_GRACE), windowEnd, plannedIds);
    }

    /** @return ids of the rows this item should own in the window. */
    private Set<String> planItem(String userId, DailyTask item, ZoneId userZone,
                                 Instant now, Instant windowEnd, LocalDate fromDate, LocalDate toDate) {
        Set<String> ids = new LinkedHashSet<>();
        if (item.getId() == null || item.getDate() == null) {
            return ids;
        }
        if (Boolean.TRUE.equals(item.getDeleted())) {
            return ids;
        }
        String itemType = item.getItemType() == null ? "TASK" : item.getItemType().toUpperCase(java.util.Locale.ROOT);
        if (!NOTIFIABLE_TYPES.contains(itemType)) {
            return ids;
        }

        ZoneId itemZone = item.getTimeZone() != null && !item.getTimeZone().isBlank()
                ? TimeZoneUtils.safeGetZoneId(item.getTimeZone())
                : userZone;

        boolean allDay = Boolean.TRUE.equals(item.getAllDay());
        LocalTime localTime = allDay ? LocalTime.of(allDayHour, 0) : parseStartTime(item.getStartTime());
        if (localTime == null) {
            return ids; // timed item with no usable start time — nothing to fire at
        }
        String kind = allDay ? ScheduledNotification.KIND_ALL_DAY : ScheduledNotification.KIND_START;

        for (LocalDate date : OccurrenceDates.between(item, fromDate, toDate)) {
            if (OccurrenceDates.isCompletedOn(item, date) || OccurrenceDates.isCancelledOn(item, date)) {
                continue;
            }
            // ZonedDateTime.of resolves DST gaps and overlaps for us, so a 02:30 alarm on a
            // spring-forward night still lands on a real instant instead of throwing.
            Instant fireAt = ZonedDateTime.of(date, localTime, itemZone).toInstant();

            // The anti-replay rule: the past is never planned.
            if (!fireAt.isAfter(now) || fireAt.isAfter(windowEnd)) {
                continue;
            }

            String id = ScheduledNotification.deterministicId(
                    userId, ScheduledNotification.SOURCE_CALENDAR_ITEM, item.getId(), date, kind);
            ids.add(id);
            upsert(id, userId, item, date, kind, fireAt, itemZone, localTime, allDay, itemType);
        }
        return ids;
    }

    private void upsert(String id, String userId, DailyTask item, LocalDate date, String kind,
                        Instant fireAt, ZoneId zone, LocalTime localTime, boolean allDay, String itemType) {
        String title = item.getTitle() == null ? "Reminder" : item.getTitle();
        String body = allDay
                ? "Today · all day"
                : "Starts now · " + localTime;
        String url = "/calendar?date=" + date;

        var existing = notificationRepository.findById(id);
        if (existing.isEmpty()) {
            ScheduledNotification row = ScheduledNotification.builder()
                    .id(id)
                    .userId(userId)
                    .sourceType(ScheduledNotification.SOURCE_CALENDAR_ITEM)
                    .sourceId(item.getId())
                    .occurrenceDate(date)
                    .kind(kind)
                    .itemType(itemType)
                    .title(title)
                    .body(body)
                    .url(url)
                    .fireAt(fireAt)
                    .zoneId(zone.getId())
                    .localTime(localTime.toString())
                    .status(NotificationStatus.SCHEDULED)
                    .nextAttemptAt(fireAt)
                    .actionToken(UUID.randomUUID().toString())
                    .expiresAt(fireAt.plus(Duration.ofDays(retentionDays)))
                    .build();
            try {
                notificationRepository.insert(row);
                log.debug("Planned {} '{}' for {} ({})", itemType, title, fireAt, zone);
            } catch (DuplicateKeyException e) {
                // Another instance planned the same occurrence between our read and write.
                // That is exactly what the deterministic id is for; nothing to do.
                log.trace("Notification {} already planned concurrently", id);
            }
            return;
        }

        ScheduledNotification row = existing.get();
        if (row.getStatus() != NotificationStatus.SCHEDULED) {
            // Already claimed, sent, missed or cancelled — the planner never rewinds a lifecycle.
            return;
        }
        boolean changed = !fireAt.equals(row.getFireAt())
                || !itemType.equals(row.getItemType())
                || !title.equals(row.getTitle())
                || !body.equals(row.getBody())
                || !url.equals(row.getUrl());
        if (!changed) {
            return;
        }
        log.debug("Re-planning notification {} (fireAt {} -> {})", id, row.getFireAt(), fireAt);
        row.setItemType(itemType);
        row.setTitle(title);
        row.setBody(body);
        row.setUrl(url);
        row.setFireAt(fireAt);
        row.setZoneId(zone.getId());
        row.setLocalTime(localTime.toString());
        row.setNextAttemptAt(fireAt);
        row.setExpiresAt(fireAt.plus(Duration.ofDays(retentionDays)));
        notificationRepository.save(row);
    }

    /**
     * Anything still SCHEDULED in the window that the plan no longer contains has lost its
     * reason to exist — the item was deleted, completed, skipped, or moved. Cancel it, so a
     * deleted event cannot still ring.
     */
    private void cancelStaleRows(String userId, Instant sweepFrom, Instant windowEnd, Set<String> plannedIds) {
        if (!windowEnd.isAfter(sweepFrom)) {
            return;
        }
        List<ScheduledNotification> pending = notificationRepository
                .findByUserIdAndStatusAndFireAtBetween(userId, NotificationStatus.SCHEDULED, sweepFrom, windowEnd);
        for (ScheduledNotification row : pending) {
            if (plannedIds.contains(row.getId())) {
                continue;
            }
            row.setStatus(NotificationStatus.CANCELLED);
            row.setLastError("source occurrence no longer scheduled");
            notificationRepository.save(row);
            log.debug("Cancelled notification {} — occurrence no longer scheduled", row.getId());
        }
    }

    /** Cancels every pending notification for a user. Used when the last device opts out. */
    public int cancelAllPending(String userId, String reason) {
        List<ScheduledNotification> pending = notificationRepository
                .findByUserIdAndStatusAndFireAtBetween(userId, NotificationStatus.SCHEDULED,
                        Instant.EPOCH, Instant.now().plus(Duration.ofDays(365)));
        for (ScheduledNotification row : pending) {
            row.setStatus(NotificationStatus.CANCELLED);
            row.setLastError(reason);
        }
        if (!pending.isEmpty()) {
            notificationRepository.saveAll(pending);
            log.info("Cancelled {} pending notification(s) for userId={} — {}", pending.size(), userId, reason);
        }
        return pending.size();
    }

    ZoneId resolveUserZone(String userId, List<PushSubscription> subscriptions) {
        String profileZone = userAccountRepository.findById(userId)
                .map(account -> account.getTimezone())
                .filter(tz -> tz != null && !tz.isBlank())
                .orElse(null);
        if (profileZone != null) {
            return TimeZoneUtils.safeGetZoneId(profileZone);
        }
        // Fall back to the most recently seen device's zone — better than guessing UTC for
        // a user who never filled in their profile.
        return subscriptions.stream()
                .filter(sub -> sub.getTimezone() != null && !sub.getTimezone().isBlank())
                .max(Comparator.comparing(sub -> sub.getLastSeenAt() == null ? Instant.EPOCH : sub.getLastSeenAt()))
                .map(sub -> TimeZoneUtils.safeGetZoneId(sub.getTimezone()))
                .orElseGet(() -> TimeZoneUtils.safeGetZoneId(defaultTimezone));
    }

    /** Tolerates "9:05", "09:05" and "09:05:00"; anything else is not a time we can fire on. */
    static LocalTime parseStartTime(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.trim();
        String[] parts = value.split(":");
        if (parts.length < 2) {
            return null;
        }
        try {
            int hour = Integer.parseInt(parts[0].trim());
            int minute = Integer.parseInt(parts[1].trim());
            int second = parts.length > 2 ? Integer.parseInt(parts[2].trim()) : 0;
            return LocalTime.of(hour, minute, second);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Test seam for the fields injected by {@code @Value}. */
    void configure(boolean enabled, long horizonMinutes, int allDayHour, String defaultTimezone, long retentionDays) {
        this.enabled = enabled;
        this.planningHorizonMinutes = horizonMinutes;
        this.allDayHour = allDayHour;
        this.defaultTimezone = defaultTimezone;
        this.retentionDays = retentionDays;
    }
}
