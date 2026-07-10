package com.personal_dashboard.backend.repository;

import com.personal_dashboard.backend.model.SleepLog;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface SleepLogRepository extends MongoRepository<SleepLog, String> {

    @Query("{ 'userId': ?0, 'date': { $gte: ?1, $lte: ?2 } }")
    List<SleepLog> findByUserIdAndDateRange(String userId, LocalDate startDate, LocalDate endDate);

    Optional<SleepLog> findByUserIdAndDate(String userId, LocalDate date);

    Optional<SleepLog> findByIdAndUserId(String id, String userId);
}
