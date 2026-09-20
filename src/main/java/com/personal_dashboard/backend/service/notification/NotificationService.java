package com.personal_dashboard.backend.service.notification;

import com.personal_dashboard.backend.dto.NotificationView;
import com.personal_dashboard.backend.dto.PushSubscriptionStatus;
import com.personal_dashboard.backend.dto.request.PushRotateRequest;
import com.personal_dashboard.backend.dto.request.PushSubscriptionRequest;
import com.personal_dashboard.backend.model.NotificationStatus;
import com.personal_dashboard.backend.model.PushSubscription;
import com.personal_dashboard.backend.model.ScheduledNotification;
import com.personal_dashboard.backend.repository.PushSubscriptionRepository;
import com.personal_dashboard.backend.repository.ScheduledNotificationRepository;
import com.personal_dashboard.backend.security.UserContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Everything the API layer needs: device registration, the notification centre feed, and
 * the read/ack/dismiss/snooze transitions. Scheduling and delivery live in
 * {@link NotificationPlanner} and {@link NotificationDispatchJob}; nothing here decides
 * when a notification fires.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

    private final PushSubscriptionRepository pushSubscriptionRepository;
    private final ScheduledNotificationRepository notificationRepository;
    private final NotificationPlanner notificationPlanner;

    @Value("${notifications.feed-days:14}")
    private long feedDays;

    @Value("${notifications.retention-days:30}")
    private long retentionDays;

    @Value("${notifications.snooze-max-minutes:1440}")
    private int snoozeMaxMinutes;

    // ── Device registration ──────────────────────────────────────────────────

    /**
     * Registers (or re-registers) this browser. Endpoints are globally unique, so a repeat
     * call from the same device updates one row instead of adding another — which is what
     * makes toggling alerts off and on again, or a page reload, harmless.
     *
     * <p>If the endpoint is already registered to a <em>different</em> account — the same
     * browser after a logout and a new login — the row is claimed by the current user.
     * Leaving it would keep pushing the previous user's private reminders to a device they
     * no longer control.
     */
    public PushSubscription register(PushSubscriptionRequest request, String userAgent) {
        String userId = UserContext.getRequiredUserId();
        Instant now = Instant.now();

        Optional<PushSubscription> existing = pushSubscriptionRepository.findByEndpoint(request.getEndpoint());
        if (existing.isPresent()) {
            PushSubscription sub = existing.get();
            if (!userId.equals(sub.getUserId())) {
                log.warn("Push endpoint {} re-claimed from userId={} by userId={} (shared device)",
                        sub.endpointOrigin(), sub.getUserId(), userId);
                sub.setUserId(userId);
            }
            applyRegistration(sub, request, userAgent, now);
            PushSubscription saved = pushSubscriptionRepository.save(sub);
            log.info("Refreshed push subscription {} for userId={} ({}, tz={})",
                    saved.getId(), userId, saved.endpointOrigin(), saved.getTimezone());
            return saved;
        }

        PushSubscription sub = PushSubscription.builder()
                .userId(userId)
                .endpoint(request.getEndpoint())
                .build();
        applyRegistration(sub, request, userAgent, now);
        try {
            PushSubscription saved = pushSubscriptionRepository.save(sub);
            log.info("Registered push subscription {} for userId={} ({}, tz={})",
                    saved.getId(), userId, saved.endpointOrigin(), saved.getTimezone());
            return saved;
        } catch (DuplicateKeyException e) {
            // Two tabs subscribed at once. The unique endpoint index caught it; adopt the winner.
            log.info("Concurrent subscribe for the same endpoint — adopting the existing row");
            PushSubscription winner = pushSubscriptionRepository.findByEndpoint(request.getEndpoint())
                    .orElseThrow(() -> e);
            winner.setUserId(userId);
            applyRegistration(winner, request, userAgent, now);
            return pushSubscriptionRepository.save(winner);
        }
    }

    private void applyRegistration(PushSubscription sub, PushSubscriptionRequest request,
                                   String userAgent, Instant now) {
        sub.setP256dh(request.getP256dh());
        sub.setAuth(request.getAuth());
        sub.setTimezone(request.getTimezone());
        sub.setDeviceId(request.getDeviceId());
        sub.setUserAgent(truncate(userAgent, 250));
        sub.setActive(true);
        sub.setInactiveReason(null);
        sub.setFailureCount(0);
        sub.setLastSeenAt(now);
    }

    /**
     * Removes this device's registration. Idempotent: unsubscribing twice, or unsubscribing
     * an endpoint the browser already dropped, is a no-op rather than an error.
     */
    public boolean unregister(String endpoint) {
        String userId = UserContext.getRequiredUserId();
        Optional<PushSubscription> existing = pushSubscriptionRepository.findByEndpoint(endpoint);
        if (existing.isEmpty()) {
            log.debug("Unsubscribe for an endpoint that is not registered (userId={})", userId);
            return false;
        }
        PushSubscription sub = existing.get();
        if (!userId.equals(sub.getUserId())) {
            // Not ours to delete; treat as already gone rather than leaking that it exists.
            log.warn("Unsubscribe rejected — endpoint {} belongs to another account", sub.endpointOrigin());
            return false;
        }
        pushSubscriptionRepository.delete(sub);
        log.info("Removed push subscription {} for userId={}", sub.getId(), userId);

        if (pushSubscriptionRepository.findByUserIdAndActiveTrue(userId).isEmpty()) {
            // Alerts are now off everywhere for this user: nothing pending should survive.
            notificationPlanner.cancelAllPending(userId, "alerts disabled on the last device");
        }
        return true;
    }

    /** Swaps an endpoint the browser rotated, keeping the same row (and the same user). */
    public boolean rotate(PushRotateRequest request) {
        Optional<PushSubscription> existing = pushSubscriptionRepository.findByEndpoint(request.getOldEndpoint());
        if (existing.isEmpty()) {
            log.info("Rotation for an unknown endpoint — ignoring");
            return false;
        }
        PushSubscription sub = existing.get();
        // Drop any stale row already sitting on the new endpoint so the unique index holds.
        pushSubscriptionRepository.findByEndpoint(request.getEndpoint())
                .filter(other -> !other.getId().equals(sub.getId()))
                .ifPresent(pushSubscriptionRepository::delete);

        sub.setEndpoint(request.getEndpoint());
        sub.setP256dh(request.getP256dh());
        sub.setAuth(request.getAuth());
        if (request.getTimezone() != null && !request.getTimezone().isBlank()) {
            sub.setTimezone(request.getTimezone());
        }
        sub.setActive(true);
        sub.setInactiveReason(null);
        sub.setFailureCount(0);
        sub.setLastSeenAt(Instant.now());
        pushSubscriptionRepository.save(sub);
        log.info("Rotated push subscription {} to a new endpoint ({})", sub.getId(), sub.endpointOrigin());
        return true;
    }

    public PushSubscriptionStatus status(String endpoint) {
        String userId = UserContext.getRequiredUserId();
        List<PushSubscription> active = pushSubscriptionRepository.findByUserIdAndActiveTrue(userId);
        Optional<PushSubscription> mine = endpoint == null || endpoint.isBlank()
                ? Optional.empty()
                : active.stream().filter(sub -> endpoint.equals(sub.getEndpoint())).findFirst();

        return PushSubscriptionStatus.builder()
                .registered(mine.isPresent())
                .subscriptionId(mine.map(PushSubscription::getId).orElse(null))
                .activeDeviceCount(active.size())
                .lastSeenAt(mine.map(PushSubscription::getLastSeenAt).orElse(null))
                .build();
    }

    // ── Notification centre ──────────────────────────────────────────────────

    /**
     * Recent notifications for the signed-in user, newest first. This is a <em>read</em>:
     * calling it can never cause anything to be delivered, which is the whole reason the
     * client is allowed to call it on every page load.
     */
    public List<NotificationView> getFeed(int limit) {
        String userId = UserContext.getRequiredUserId();
        Instant since = Instant.now().minus(Duration.ofDays(feedDays));
        return notificationRepository
                .findFeed(userId, since, Sort.by(Sort.Direction.DESC, "fireAt"))
                .stream()
                .limit(Math.max(1, Math.min(limit, 200)))
                .map(NotificationView::from)
                .toList();
    }

    /** Called by a client that actually displayed the notification. SENT → DELIVERED. */
    public NotificationView acknowledge(String id) {
        ScheduledNotification row = requireOwned(id);
        if (row.getAcknowledgedAt() == null) {
            row.setAcknowledgedAt(Instant.now());
        }
        if (row.getStatus() == NotificationStatus.SENT) {
            row.setStatus(NotificationStatus.DELIVERED);
        }
        return NotificationView.from(notificationRepository.save(row));
    }

    public NotificationView markRead(String id) {
        ScheduledNotification row = requireOwned(id);
        if (row.getReadAt() == null) {
            row.setReadAt(Instant.now());
        }
        return NotificationView.from(notificationRepository.save(row));
    }

    public int markAllRead() {
        String userId = UserContext.getRequiredUserId();
        List<ScheduledNotification> unread = notificationRepository.findUnread(userId);
        Instant now = Instant.now();
        unread.forEach(row -> row.setReadAt(now));
        if (!unread.isEmpty()) {
            notificationRepository.saveAll(unread);
        }
        return unread.size();
    }

    public void dismiss(String id) {
        ScheduledNotification row = requireOwned(id);
        row.setDismissedAt(Instant.now());
        notificationRepository.save(row);
    }

    public int dismissAll() {
        String userId = UserContext.getRequiredUserId();
        Instant since = Instant.now().minus(Duration.ofDays(feedDays));
        List<ScheduledNotification> feed = notificationRepository
                .findFeed(userId, since, Sort.by(Sort.Direction.DESC, "fireAt"));
        Instant now = Instant.now();
        feed.forEach(row -> row.setDismissedAt(now));
        if (!feed.isEmpty()) {
            notificationRepository.saveAll(feed);
        }
        return feed.size();
    }

    // ── Snooze ───────────────────────────────────────────────────────────────

    /**
     * Re-schedules a notification from the OS notification's own snooze button, using the
     * capability token that came with the push. A brand new row is created rather than
     * rewinding the delivered one, so history stays truthful — and because the new row's id
     * is derived from the minute it was requested, a double-tap produces one snooze, not two.
     */
    public Optional<NotificationView> snoozeByToken(String actionToken, Integer requestedMinutes) {
        int minutes = Math.max(1, Math.min(requestedMinutes == null ? 10 : requestedMinutes, snoozeMaxMinutes));
        Optional<ScheduledNotification> found = notificationRepository.findByActionToken(actionToken);
        if (found.isEmpty()) {
            log.warn("Snooze requested with an unknown or expired action token");
            return Optional.empty();
        }
        ScheduledNotification origin = found.get();
        Instant now = Instant.now();
        Instant fireAt = now.plus(Duration.ofMinutes(minutes));

        String snoozeId = origin.getId() + "|snooze|" + (now.getEpochSecond() / 60);
        ScheduledNotification snoozed = ScheduledNotification.builder()
                .id(snoozeId)
                .userId(origin.getUserId())
                .sourceType(origin.getSourceType())
                .sourceId(origin.getSourceId())
                .occurrenceDate(origin.getOccurrenceDate())
                .kind(origin.getKind())
                .itemType(origin.getItemType())
                .title(origin.getTitle())
                .body(origin.getBody())
                .url(origin.getUrl())
                .fireAt(fireAt)
                .zoneId(origin.getZoneId())
                .localTime(origin.getLocalTime())
                .status(NotificationStatus.SCHEDULED)
                .nextAttemptAt(fireAt)
                .actionToken(UUID.randomUUID().toString())
                .expiresAt(fireAt.plus(Duration.ofDays(retentionDays)))
                .build();
        try {
            notificationRepository.insert(snoozed);
            log.info("Snoozed notification {} for {} minutes (new row {})", origin.getId(), minutes, snoozeId);
        } catch (DuplicateKeyException e) {
            log.debug("Snooze already recorded for this minute — ignoring the repeat");
            return notificationRepository.findById(snoozeId).map(NotificationView::from);
        }
        return Optional.of(NotificationView.from(snoozed));
    }

    private ScheduledNotification requireOwned(String id) {
        String userId = UserContext.getRequiredUserId();
        return notificationRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new IllegalArgumentException("Notification not found: " + id));
    }

    private static String truncate(String value, int max) {
        if (value == null) return null;
        return value.length() <= max ? value : value.substring(0, max);
    }

    /** Test seam for the fields injected by {@code @Value}. */
    void configure(long feedDays, long retentionDays, int snoozeMaxMinutes) {
        this.feedDays = feedDays;
        this.retentionDays = retentionDays;
        this.snoozeMaxMinutes = snoozeMaxMinutes;
    }
}
