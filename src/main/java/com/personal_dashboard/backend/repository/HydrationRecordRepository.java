package com.personal_dashboard.backend.repository;

import com.personal_dashboard.backend.model.HydrationRecord;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface HydrationRecordRepository extends MongoRepository<HydrationRecord, String> {

    Optional<HydrationRecord> findFirstByDate(LocalDate date);

    List<HydrationRecord> findByDateBetween(LocalDate startDate, LocalDate endDate);

    Optional<HydrationRecord> findTopByDateBetweenOrderByDateDesc(LocalDate startDate, LocalDate endDate);

    Optional<HydrationRecord> findFirstByDateBetween(LocalDate startDate, LocalDate endDate);
}
