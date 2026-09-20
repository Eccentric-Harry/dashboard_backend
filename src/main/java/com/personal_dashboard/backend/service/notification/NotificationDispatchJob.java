package com.personal_dashboard.backend.service.notification;

import com.personal_dashboard.backend.model.DailyTask;
import com.personal_dashboard.backend.model.NotificationDelivery;
import com.personal_dashboard.backend.model.NotificationStatus;
import com.personal_dashboard.backend.model.PushSubscription;
import com.personal_dashboard.backend.model.ScheduledNotification;
import com.personal_dashboard.backend.repository.DailyTaskRepository;
import com.personal_dashboard.backend.repository.PushSubscriptionRepository;
import com.personal_dashboard.backend.repository.ScheduledNotificationRepository;
import com.personal_dashboard.backend.service.PushNotificationService;
import com.personal_dashboard.backend.util.OccurrenceDates;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Delivers notifications whose instant has arrived.
 *
 * <p>The loop is: claim one row atomically, re-verify it still deserves to fire, push it to
 * every device that has not already received it, then record the outcome. Every step is
 * designed around the fact that it may run twice:
 *
 * <ul>
 *   <li>the claim is a database-level compare-and-set, so only one worker gets a row;</li>
 *   <li>per-device delivery state means a retry only re-pushes endpoints that failed;</li>
 *   <li>a row is marked SENT only <em>after</em> a push service accepted it — the previous
 *       implementation wrote its dedupe record first, so a failed send was indistinguishable
 *       from a delivered one and the notification was simply lost;</li>
 *   <li>a row that became due too long ago is marked MISSED rather than fired, which is what
 *       keeps a restart after an outage from dumping hours of backlog onto a user.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationDispatchJob {

    private final NotificationClaimStore claimStore;
    private final ScheduledNotificationRepository notificationRepository;
    private final PushSubscriptionRepository pushSubscriptionRepository;
    private final DailyTaskRepository dailyTaskRepository;
    private final PushNotificationService pushNotificationService;

    /** Identifies this process in lock records; useful when several instances are running. */
    private final String instanceId = UUID.randomUUID().toString().substring(0, 8);

    @Value("${notifications.enabled:true}")
    private boolean enabled;

    /** Most rows one tick will handle, so a backlog cannot monopolise the scheduler thread. */
    @Value("${notifications.dispatch-batch-size:50}")
    private int batchSize;

    /** Beyond this much lateness a notification is no longer useful and is never sent. */
    @Value("${notifications.max-lateness-minutes:30}")
    private long maxLatenessMinutes;

    /** A PROCESSING row untouched for this long is assumed abandoned. */
    @Value("${notifications.claim-timeout-minutes:5}")
    private long claimTimeoutMinutes;

    @Value("${notifications.max-attempts:6}")
    private int maxAttempts;

    @Scheduled(fixedDelayString = "${notifications.dispatch-interval-ms:15000}",
            initialDelayString = "${notifications.dispatch-initial-delay-ms:15000}")
    public void dispatch() {
        if (!enabled) {
            return;
        }
        try {
            runCycle(Instant.now());
        } catch (RuntimeException e) {
            log.error("Notification dispatch cycle failed", e);
        }
    }

    /** Visible for testing. */
    public int runCycle(Instant now) {
        claimStore.releaseStaleClaims(now.minus(Duration.ofMinutes(claimTimeoutMinutes)));

        int processed = 0;
        while (processed < batchSize) {
            ScheduledNotification claimed = claimStore.claimNext(now, instanceId);
            if (claimed == null) {
                break;
            }
            processed++;
            try {
                process(claimed, now);
            } catch (RuntimeException e) {
                // Never leave a row stuck in PROCESSING because of an unexpected failure.
                log.error("Unexpected failure dispatching notification {}", claimed.getId(), e);
                fail(claimed, "dispatch error: " + e.getMessage());
            }
        }
        return processed;
    }

    void process(ScheduledNotification row, Instant now) {
        // 1. Too late to be useful? Deliberately drop it, loudly.
        Duration lateness = Duration.between(row.getFireAt(), now);
        if (lateness.compareTo(Duration.ofMinutes(maxLatenessMinutes)) > 0) {
            row.setStatus(NotificationStatus.MISSED);
            row.setLastError("skipped: " + lateness.toMinutes() + " minutes late (limit "
                    + maxLatenessMinutes + ")");
            clearLock(row);
            notificationRepository.save(row);
            log.warn("Notification {} missed its window by {} minutes — not delivering (source={}, fireAt={})",
                    row.getId(), lateness.toMinutes(), row.getSourceId(), row.getFireAt());
            return;
        }

        // 2. Still worth sending? The source may have changed since it was planned.
        String cancellation = cancellationReason(row);
        if (cancellation != null) {
            row.setStatus(NotificationStatus.CANCELLED);
            row.setLastError(cancellation);
            clearLock(row);
            notificationRepository.save(row);
            log.info("Notification {} cancelled at dispatch time — {}", row.getId(), cancellation);
            return;
        }

        // 3. Which devices are still opted in?
        List<PushSubscription> subscriptions = pushSubscriptionRepository.findByUserIdAndActiveTrue(row.getUserId());
        if (subscriptions.isEmpty()) {
            row.setStatus(NotificationStatus.CANCELLED);
            row.setLastError("no active push subscriptions — alerts are off for this user");
            clearLock(row);
            notificationRepository.save(row);
            log.info("Notification {} cancelled — user {} has no active devices", row.getId(), row.getUserId());
            return;
        }

        deliver(row, subscriptions, now);
    }

    private void deliver(ScheduledNotification row, List<PushSubscription> subscriptions, Instant now) {
        String payload = pushNotificationService.buildPayload(payloadOf(row));

        // Existing per-device state, so a retry never re-pushes an endpoint that already took it.
        Map<String, NotificationDelivery> byEndpoint = new LinkedHashMap<>();
        for (NotificationDelivery delivery : row.getDeliveries() == null ? List.<NotificationDelivery>of() : row.getDeliveries()) {
            if (delivery.getSubscriptionId() != null) {
                byEndpoint.put(delivery.getSubscriptionId(), delivery);
            }
        }

        boolean anyAccepted = false;
        boolean anyRetryable = false;

        for (PushSubscription sub : subscriptions) {
            NotificationDelivery delivery = byEndpoint.get(sub.getId());
            if (delivery == null) {
                delivery = NotificationDelivery.builder()
                        .subscriptionId(sub.getId())
                        .endpointOrigin(sub.endpointOrigin())
                        .build();
                byEndpoint.put(sub.getId(), delivery);
            }
            if (delivery.isTerminal()) {
                anyAccepted |= delivery.isSent();
                continue; // already settled for this device
            }

            PushNotificationService.PushOutcome outcome = pushNotificationService.send(sub, payload);
            delivery.setAttempts(delivery.getAttempts() + 1);
            delivery.setStatusCode(outcome.statusCode());
            delivery.setLastError(outcome.message());

            switch (outcome.kind()) {
                case ACCEPTED -> {
                    delivery.setStatus("SENT");
                    delivery.setSentAt(now);
                    anyAccepted = true;
                    markSubscriptionHealthy(sub, now);
                    log.info("Notification {} accepted for device {} ({})",
                            row.getId(), sub.getId(), sub.endpointOrigin());
                }
                case EXPIRED -> {
                    delivery.setStatus("EXPIRED");
                    deactivateSubscription(sub, "endpoint gone (" + outcome.statusCode() + ")", now);
                }
                case PERMANENT -> {
                    delivery.setStatus("FAILED");
                    deactivateSubscription(sub, "permanent push failure (" + outcome.statusCode() + ")", now);
                    log.error("Notification {} permanently rejected for device {}: {} {}",
                            row.getId(), sub.getId(), outcome.statusCode(), outcome.message());
                }
                case RETRYABLE -> {
                    anyRetryable = true;
                    markSubscriptionFailure(sub, outcome.message(), now);
                    log.warn("Notification {} temporarily failed for device {}: {} {}",
                            row.getId(), sub.getId(), outcome.statusCode(), outcome.message());
                }
            }
        }

        row.setDeliveries(new ArrayList<>(byEndpoint.values()));
        clearLock(row);

        if (anyAccepted) {
            row.setStatus(NotificationStatus.SENT);
            row.setSentAt(row.getSentAt() == null ? now : row.getSentAt());
            row.setLastError(null);
            notificationRepository.save(row);
            return;
        }

        int attempts = row.getAttempts() + 1;
        row.setAttempts(attempts);
        if (anyRetryable && attempts < maxAttempts) {
            long backoff = backoffMillis(attempts);
            row.setStatus(NotificationStatus.SCHEDULED);
            row.setNextAttemptAt(now.plusMillis(backoff));
            row.setLastError("no device accepted the push yet; retry " + attempts);
            notificationRepository.save(row);
            log.warn("Notification {} not delivered (attempt {}); retrying in {}ms", row.getId(), attempts, backoff);
            return;
        }

        fail(row, anyRetryable
                ? "retry budget exhausted after " + attempts + " attempts"
                : "no device could be reached");
    }

    /** Re-checks the source right before firing, so a deletion or tick cannot be out-raced. */
    private String cancellationReason(ScheduledNotification row) {
        if (!ScheduledNotification.SOURCE_CALENDAR_ITEM.equals(row.getSourceType())) {
            return null;
        }
        DailyTask task = dailyTaskRepository.findByIdAndUserId(row.getSourceId(), row.getUserId()).orElse(null);
        if (task == null) {
            return "source item no longer exists";
        }
        if (Boolean.TRUE.equals(task.getDeleted())) {
            return "source item was deleted";
        }
        if (OccurrenceDates.isCompletedOn(task, row.getOccurrenceDate())) {
            return "occurrence already completed";
        }
        if (OccurrenceDates.isCancelledOn(task, row.getOccurrenceDate())) {
            return "occurrence was cancelled";
        }
        if (task.getExcludedDates() != null && task.getExcludedDates().contains(row.getOccurrenceDate())) {
            return "occurrence was skipped";
        }
        return null;
    }

    private Map<String, Object> payloadOf(ScheduledNotification row) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", row.getId());
        payload.put("title", row.getTitle());
        payload.put("body", row.getBody());
        payload.put("url", row.getUrl());
        // The tag is the record id: if the same notification ever reaches a device twice,
        // the browser replaces the first rather than stacking a duplicate.
        payload.put("tag", row.getId());
        payload.put("sourceId", row.getSourceId());
        payload.put("sourceType", row.getSourceType());
        payload.put("itemType", row.getItemType());
        payload.put("occurrenceDate", String.valueOf(row.getOccurrenceDate()));
        payload.put("fireAt", row.getFireAt() == null ? null : row.getFireAt().toString());
        payload.put("actionToken", row.getActionToken());
        return payload;
    }

    private void fail(ScheduledNotification row, String reason) {
        row.setStatus(NotificationStatus.FAILED);
        row.setLastError(reason);
        clearLock(row);
        notificationRepository.save(row);
        log.error("Notification {} FAILED for userId={} — {}", row.getId(), row.getUserId(), reason);
    }

    private void clearLock(ScheduledNotification row) {
        row.setLockedAt(null);
        row.setLockOwner(null);
    }

    private void markSubscriptionHealthy(PushSubscription sub, Instant now) {
        if (sub.getFailureCount() == 0 && sub.getLastSuccessAt() != null) {
            return; // nothing to write
        }
        sub.setFailureCount(0);
        sub.setLastSuccessAt(now);
        pushSubscriptionRepository.save(sub);
    }

    private void markSubscriptionFailure(PushSubscription sub, String error, Instant now) {
        sub.setFailureCount(sub.getFailureCount() + 1);
        sub.setLastFailureAt(now);
        if (sub.getFailureCount() >= 20) {
            // Twenty consecutive transport failures is a dead device, not a blip.
            deactivateSubscription(sub, "too many consecutive failures: " + error, now);
            return;
        }
        pushSubscriptionRepository.save(sub);
    }

    private void deactivateSubscription(PushSubscription sub, String reason, Instant now) {
        sub.setActive(false);
        sub.setInactiveReason(reason);
        sub.setLastFailureAt(now);
        pushSubscriptionRepository.save(sub);
        log.info("Deactivated push subscription {} ({}) — {}", sub.getId(), sub.endpointOrigin(), reason);
    }

    /** Exponential backoff with full jitter, capped at five minutes. */
    static long backoffMillis(int attempt) {
        long base = 5_000L << Math.min(attempt - 1, 6);
        long capped = Math.min(base, 300_000L);
        return ThreadLocalRandom.current().nextLong(1_000L, capped + 1);
    }

    /** Test seam for the fields injected by {@code @Value}. */
    void configure(boolean enabled, int batchSize, long maxLatenessMinutes, long claimTimeoutMinutes, int maxAttempts) {
        this.enabled = enabled;
        this.batchSize = batchSize;
        this.maxLatenessMinutes = maxLatenessMinutes;
        this.claimTimeoutMinutes = claimTimeoutMinutes;
        this.maxAttempts = maxAttempts;
    }
}
