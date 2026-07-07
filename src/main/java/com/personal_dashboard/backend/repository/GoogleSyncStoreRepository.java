package com.personal_dashboard.backend.repository;

import com.personal_dashboard.backend.model.GoogleSyncStore;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface GoogleSyncStoreRepository extends MongoRepository<GoogleSyncStore, String> {

    List<GoogleSyncStore> findByUserId(String userId);

    Optional<GoogleSyncStore> findByUserIdAndEmail(String userId, String email);

    boolean existsByUserId(String userId);

    Optional<GoogleSyncStore> findByWebhookChannelId(String webhookChannelId);

    void deleteByUserId(String userId);

    void deleteByUserIdAndEmail(String userId, String email);
}
