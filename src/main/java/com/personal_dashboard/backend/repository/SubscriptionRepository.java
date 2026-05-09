package com.personal_dashboard.backend.repository;

import com.personal_dashboard.backend.model.Subscription;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface SubscriptionRepository extends MongoRepository<Subscription, String> {

    Optional<Subscription> findByName(String name);
}
