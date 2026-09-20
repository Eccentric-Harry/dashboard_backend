package com.personal_dashboard.backend.service.notification;

import com.personal_dashboard.backend.model.NotificationStatus;
import com.personal_dashboard.backend.model.ScheduledNotification;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Hands out exclusive claims on due notifications.
 *
 * <p>Claiming is a single {@code findAndModify}: the status flips SCHEDULED → PROCESSING
 * inside the database, so two dispatcher threads — or two application instances behind a
 * load balancer — cannot both get the same row. A "check then update" would look correct
 * in a single-instance test and double-send the first time the service is scaled out.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationClaimStore {

    private final MongoTemplate mongoTemplate;

    /**
     * Atomically claims the next due notification, or returns {@code null} when there is
     * nothing to do. Only rows whose fire time has arrived and whose retry backoff has
     * elapsed are eligible.
     */
    public ScheduledNotification claimNext(Instant now, String owner) {
        Query query = new Query(new Criteria().andOperator(
                Criteria.where("status").is(NotificationStatus.SCHEDULED.name()),
                Criteria.where("fireAt").lte(now),
                new Criteria().orOperator(
                        Criteria.where("nextAttemptAt").is(null),
                        Criteria.where("nextAttemptAt").lte(now))))
                .with(org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.ASC, "fireAt"));

        Update update = new Update()
                .set("status", NotificationStatus.PROCESSING.name())
                .set("lockedAt", now)
                .set("lockOwner", owner);

        return mongoTemplate.findAndModify(query, update,
                FindAndModifyOptions.options().returnNew(true), ScheduledNotification.class);
    }

    /**
     * Returns rows a dispatcher claimed and never finished (it crashed, was redeployed, or
     * the pod was evicted) to SCHEDULED so another worker can pick them up. Without this a
     * single crash would silently swallow every in-flight notification.
     */
    public long releaseStaleClaims(Instant staleBefore) {
        Query query = new Query(new Criteria().andOperator(
                Criteria.where("status").is(NotificationStatus.PROCESSING.name()),
                Criteria.where("lockedAt").lt(staleBefore)));

        Update update = new Update()
                .set("status", NotificationStatus.SCHEDULED.name())
                .unset("lockedAt")
                .unset("lockOwner");

        long released = mongoTemplate.updateMulti(query, update, ScheduledNotification.class).getModifiedCount();
        if (released > 0) {
            log.warn("Released {} stale notification claim(s) left behind by a previous dispatcher", released);
        }
        return released;
    }
}
