package com.personal_dashboard.backend.repository;

import com.personal_dashboard.backend.model.DailyLog;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface DailyLogRepository extends MongoRepository<DailyLog, String> {

    Optional<DailyLog> findByDate(LocalDate date);

    List<DailyLog> findByDateBetween(LocalDate startDate, LocalDate endDate);

    List<DailyLog> findByDailyOneThingCompleted(Boolean completed);
}
