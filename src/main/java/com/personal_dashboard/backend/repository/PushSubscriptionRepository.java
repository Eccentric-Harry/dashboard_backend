package com.personal_dashboard.backend.repository;

import com.personal_dashboard.backend.model.PushSubscription;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface PushSubscriptionRepository extends MongoRepository<PushSubscription, String> {
    Optional<PushSubscription> findByEndpoint(String endpoint);
}
