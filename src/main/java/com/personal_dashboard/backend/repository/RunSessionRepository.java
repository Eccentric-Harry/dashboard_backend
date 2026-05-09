package com.personal_dashboard.backend.repository;

import com.personal_dashboard.backend.model.RunSession;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface RunSessionRepository extends MongoRepository<RunSession, String> {

    List<RunSession> findByDate(LocalDate date);

    List<RunSession> findByDateBetween(LocalDate startDate, LocalDate endDate);

    List<RunSession> findByTitle(String title);
}
