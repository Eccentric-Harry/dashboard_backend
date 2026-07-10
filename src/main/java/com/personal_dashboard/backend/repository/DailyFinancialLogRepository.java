package com.personal_dashboard.backend.repository;

import com.personal_dashboard.backend.model.DailyFinancialLog;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface DailyFinancialLogRepository extends MongoRepository<DailyFinancialLog, String> {

    Optional<DailyFinancialLog> findByUserIdAndDateString(String userId, String dateString);

    List<DailyFinancialLog> findByUserIdAndDateBetween(String userId, Instant startDate, Instant endDate);

    @Query("{ 'userId': ?0, 'dateString': { $gte: ?1, $lte: ?2 } }")
    List<DailyFinancialLog> findByUserIdAndDateStringBetween(String userId, String startDateString, String endDateString);

    List<DailyFinancialLog> findByUserId(String userId);
}
