package com.personal_dashboard.backend.repository;

import com.personal_dashboard.backend.model.Learning;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface LearningRepository extends MongoRepository<Learning, String> {

    @Query("{ 'date': { $gte: ?0, $lte: ?1 } }")
    List<Learning> findByDateRange(LocalDate startDate, LocalDate endDate);

    List<Learning> findByDateIn(java.util.Collection<LocalDate> dates);
}
