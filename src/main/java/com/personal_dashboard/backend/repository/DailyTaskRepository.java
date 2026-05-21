package com.personal_dashboard.backend.repository;

import com.personal_dashboard.backend.model.DailyTask;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface DailyTaskRepository extends MongoRepository<DailyTask, String> {

    List<DailyTask> findByDate(LocalDate date);

    List<DailyTask> findByDateBetween(LocalDate startDate, LocalDate endDate);
}
