package com.personal_dashboard.backend.repository;

import com.personal_dashboard.backend.model.PushSubscription;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PushSubscriptionRepository extends MongoRepository<PushSubscription, String> {

    /** Endpoints are globally unique, so this is the identity lookup — not the user-scoped one. */
    Optional<PushSubscription> findByEndpoint(String endpoint);

    Optional<PushSubscription> findByUserIdAndEndpoint(String userId, String endpoint);

    List<PushSubscription> findByUserId(String userId);

    /** Devices the dispatcher may actually push to. */
    List<PushSubscription> findByUserIdAndActiveTrue(String userId);

    /** Drives the planner's "which users have alerts on" question. */
    List<PushSubscription> findByActiveTrue();
}
