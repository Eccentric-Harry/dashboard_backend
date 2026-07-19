package com.personal_dashboard.backend.repository;

import com.personal_dashboard.backend.model.DailyRing;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface DailyRingRepository extends MongoRepository<DailyRing, String> {

    Optional<DailyRing> findByUserIdAndDate(String userId, LocalDate date);

    @Query("{ 'userId': ?0, 'date': { $gte: ?1, $lte: ?2 } }")
    List<DailyRing> findByUserIdAndDateRange(String userId, LocalDate startDate, LocalDate endDate);

    List<DailyRing> findByUserIdOrderByDateAsc(String userId);
}
