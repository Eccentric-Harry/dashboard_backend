package com.personal_dashboard.backend.repository;

import com.personal_dashboard.backend.model.DailyHealthRecord;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface DailyHealthRecordRepository extends MongoRepository<DailyHealthRecord, String> {

    Optional<DailyHealthRecord> findByDate(LocalDate date);

    List<DailyHealthRecord> findByDateBetween(LocalDate startDate, LocalDate endDate);
}
