package com.personal_dashboard.backend.repository;

import com.personal_dashboard.backend.model.NutrientCacheEntry;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface NutrientCacheRepository extends MongoRepository<NutrientCacheEntry, String> {

    Optional<NutrientCacheEntry> findByLookupKey(String lookupKey);
}
