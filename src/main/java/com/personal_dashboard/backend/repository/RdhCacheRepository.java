package com.personal_dashboard.backend.repository;

import com.personal_dashboard.backend.model.RdhCacheEntry;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface RdhCacheRepository extends MongoRepository<RdhCacheEntry, String> {
}
