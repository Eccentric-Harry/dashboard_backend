package com.personal_dashboard.backend.repository;

import com.personal_dashboard.backend.model.GoogleSyncStore;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface GoogleSyncStoreRepository extends MongoRepository<GoogleSyncStore, String> {
    Optional<GoogleSyncStore> findByWebhookChannelId(String webhookChannelId);
}
