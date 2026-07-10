package com.personal_dashboard.backend.repository;

import com.personal_dashboard.backend.model.DailyFinancialLog;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface DailyFinancialLogRepository extends MongoRepository<DailyFinancialLog, String> {

    Optional<DailyFinancialLog> findByUserIdAndDateString(String userId, String dateString);

    List<DailyFinancialLog> findByUserIdAndDateBetween(String userId, Instant startDate, Instant endDate);

    List<DailyFinancialLog> findByUserIdAndDateStringGreaterThanEqualAndDateStringLessThanEqual(
            String userId, String startDateString, String endDateString);

    List<DailyFinancialLog> findByUserId(String userId);
}
