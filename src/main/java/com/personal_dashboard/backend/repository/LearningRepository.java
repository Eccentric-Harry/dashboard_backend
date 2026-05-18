package com.personal_dashboard.backend.repository;

import com.personal_dashboard.backend.model.Learning;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface LearningRepository extends MongoRepository<Learning, String> {

    List<Learning> findByDate(LocalDate date);

    List<Learning> findByDateBetween(LocalDate startDate, LocalDate endDate);
}
