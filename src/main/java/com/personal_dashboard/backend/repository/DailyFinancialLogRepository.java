package com.personal_dashboard.backend.repository;

import com.personal_dashboard.backend.model.DailyFinancialLog;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public interface DailyFinancialLogRepository extends MongoRepository<DailyFinancialLog, String> {
    List<DailyFinancialLog> findByDateBetween(Instant startDate, Instant endDate);
}
