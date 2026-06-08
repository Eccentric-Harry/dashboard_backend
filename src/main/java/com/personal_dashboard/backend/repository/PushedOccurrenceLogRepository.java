package com.personal_dashboard.backend.repository;

import com.personal_dashboard.backend.model.PushedOccurrenceLog;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface PushedOccurrenceLogRepository extends MongoRepository<PushedOccurrenceLog, String> {
}
