package com.personal_dashboard.backend.service.notification;

import com.personal_dashboard.backend.model.DailyTask;
import com.personal_dashboard.backend.model.PushSubscription;
import com.personal_dashboard.backend.repository.PushSubscriptionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.mapping.event.AbstractMongoEventListener;
import org.springframework.data.mongodb.core.mapping.event.AfterSaveEvent;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Re-plans a user's notifications as soon as one of their calendar items changes, instead of
 * waiting for the next planner tick.
 *
 * <p>Without this there is a real hole: the planner sweeps once a minute, so a task created
 * at 07:34:30 for 07:35:00 is never materialised — the sweep that would have planned it runs
 * after it was already due, and the planner never plans the past. Someone adding a reminder
 * for "in two minutes" is exactly the case that has to work.
 *
 * <p>Planning is idempotent (deterministic ids), so running it again here costs one query and
 * can never duplicate anything. Failures are swallowed: a notification that has to wait for
 * the next sweep is a delay, but a save that fails because of a planning error is data loss.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationReplanListener extends AbstractMongoEventListener<DailyTask> {

    private final NotificationPlanner notificationPlanner;
    private final PushSubscriptionRepository pushSubscriptionRepository;

    @Value("${notifications.enabled:true}")
    private boolean enabled;

    @Value("${notifications.replan-on-write:true}")
    private boolean replanOnWrite;

    @Override
    public void onAfterSave(AfterSaveEvent<DailyTask> event) {
        if (!enabled || !replanOnWrite) {
            return;
        }
        DailyTask task = event.getSource();
        String userId = task.getUserId();
        if (userId == null || userId.isBlank()) {
            return;
        }
        try {
            List<PushSubscription> devices = pushSubscriptionRepository.findByUserIdAndActiveTrue(userId);
            if (devices.isEmpty()) {
                return; // alerts are off for this user; nothing to plan
            }
            notificationPlanner.planForUser(userId, devices, Instant.now());
            log.debug("Re-planned notifications for userId={} after a write to task {}", userId, task.getId());
        } catch (RuntimeException e) {
            log.warn("Re-plan after write failed for userId={} (the next planner sweep will pick it up): {}",
                    userId, e.getMessage());
        }
    }
}
