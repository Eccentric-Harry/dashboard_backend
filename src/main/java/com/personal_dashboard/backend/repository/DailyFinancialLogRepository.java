package com.personal_dashboard.backend.repository;

import com.personal_dashboard.backend.model.DailyFinancialLog;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface DailyFinancialLogRepository extends MongoRepository<DailyFinancialLog, String> {
    List<DailyFinancialLog> findByDateBetween(Instant startDate, Instant endDate);

    Optional<DailyFinancialLog> findByUserIdAndDateString(String userId, String dateString);

    List<DailyFinancialLog> findByUserIdAndDateBetween(String userId, Instant startDate, Instant endDate);
}
