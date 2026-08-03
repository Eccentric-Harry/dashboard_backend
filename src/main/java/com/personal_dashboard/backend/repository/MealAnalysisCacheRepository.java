package com.personal_dashboard.backend.repository;

import com.personal_dashboard.backend.model.MealAnalysisCacheEntry;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface MealAnalysisCacheRepository extends MongoRepository<MealAnalysisCacheEntry, String> {

    Optional<MealAnalysisCacheEntry> findByCacheKey(String cacheKey);
}
