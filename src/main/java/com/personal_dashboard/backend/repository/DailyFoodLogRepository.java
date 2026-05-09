package com.personal_dashboard.backend.repository;

import com.personal_dashboard.backend.model.DailyFoodLog;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface DailyFoodLogRepository extends MongoRepository<DailyFoodLog, String> {

    Optional<DailyFoodLog> findByMealId(String mealId);

    List<DailyFoodLog> findByDateBetween(LocalDate startDate, LocalDate endDate);

    List<DailyFoodLog> findByDateBetweenOrderByDateDesc(LocalDate startDate, LocalDate endDate);

    List<DailyFoodLog> findByDateGreaterThanEqualAndDateLessThanEqualOrderByDateDesc(LocalDate startDate, LocalDate endDate);
}
