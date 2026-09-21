package com.personal_dashboard.backend.repository;

import com.personal_dashboard.backend.model.NotificationStatus;
import com.personal_dashboard.backend.model.ScheduledNotification;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface ScheduledNotificationRepository extends MongoRepository<ScheduledNotification, String> {

    Optional<ScheduledNotification> findByIdAndUserId(String id, String userId);

    Optional<ScheduledNotification> findByActionToken(String actionToken);

    /** Rows the planner may still revise — everything already sent is off limits. */
    List<ScheduledNotification> findByUserIdAndStatusAndFireAtBetween(
            String userId, NotificationStatus status, Instant from, Instant to);

    List<ScheduledNotification> findByUserIdAndSourceIdAndStatus(
            String userId, String sourceId, NotificationStatus status);

    /**
     * The notification centre feed: everything that actually reached (or failed to reach)
     * the user, newest first. Purely scheduled rows are future work and are not shown.
     */
    @Query("{ 'userId': ?0, 'dismissedAt': null, 'status': { $in: ['SENT', 'DELIVERED', 'FAILED'] }, 'fireAt': { $gte: ?1 } }")
    List<ScheduledNotification> findFeed(String userId, Instant since,
                                         org.springframework.data.domain.Sort sort);

    @Query("{ 'userId': ?0, 'dismissedAt': null, 'readAt': null, 'status': { $in: ['SENT', 'DELIVERED'] } }")
    List<ScheduledNotification> findUnread(String userId);

    /** Everything that reached the dispatcher, whatever the outcome — the diagnostics view. */
    @Query("{ 'userId': ?0, 'fireAt': { $gte: ?1 }, 'status': { $in: ['PROCESSING', 'SENT', 'DELIVERED', 'FAILED', 'MISSED', 'CANCELLED'] } }")
    List<ScheduledNotification> findRecentAttempts(String userId, Instant since,
                                                   org.springframework.data.domain.Sort sort);
}
