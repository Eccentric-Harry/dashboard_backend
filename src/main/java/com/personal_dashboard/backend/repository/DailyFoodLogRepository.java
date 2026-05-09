package com.personal_dashboard.backend.repository;

import com.personal_dashboard.backend.model.DailyFoodLog;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface DailyFoodLogRepository extends MongoRepository<DailyFoodLog, String> {

    Optional<DailyFoodLog> findByMealId(String mealId);

    /**
     * Find daily food logs where the date is within the inclusive range.
     * Uses @Query to ensure inclusive bounds ($gte/$lte) since Spring Data's
     * 'Between' can be exclusive on boundaries for date types.
     */
    @Query("{ 'date': { $gte: ?0, $lte: ?1 } }")
    List<DailyFoodLog> findByDateRange(LocalDate startDate, LocalDate endDate);

    /**
     * Find daily food logs by mealId (date string) range using string comparison.
     * Since mealId is the date string "YYYY-MM-DD", lexicographic comparison works.
     */
    @Query("{ 'mealId': { $gte: ?0, $lte: ?1 } }")
    List<DailyFoodLog> findByMealIdRange(String startMealId, String endMealId);
}
