package com.personal_dashboard.backend.repository;

import com.personal_dashboard.backend.model.DailyLog;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface DailyLogRepository extends MongoRepository<DailyLog, String> {

    @Query("{ 'date': { $gte: ?0, $lte: ?1 } }")
    List<DailyLog> findByDateRange(LocalDate startDate, LocalDate endDate);

    List<DailyLog> findByDateIn(java.util.Collection<LocalDate> dates);

}
