package com.personal_dashboard.backend.repository;

import com.personal_dashboard.backend.model.PushedOccurrenceLog;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;

@Repository
public interface PushedOccurrenceLogRepository extends MongoRepository<PushedOccurrenceLog, String> {
    boolean existsByUserIdAndSubscriptionIdAndTaskIdAndOccurrenceDate(String userId, String subscriptionId, String taskId, LocalDate occurrenceDate);
}
