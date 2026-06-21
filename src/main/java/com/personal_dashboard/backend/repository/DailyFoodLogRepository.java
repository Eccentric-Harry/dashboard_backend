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

    Optional<DailyFoodLog> findByUserIdAndDateString(String userId, String dateString);

    Optional<DailyFoodLog> findByIdAndUserId(String id, String userId);

    /**
     * Find daily food logs where the date is within the inclusive range.
     * Uses @Query to ensure inclusive bounds ($gte/$lte) since Spring Data's
     * 'Between' can be exclusive on boundaries for date types.
     */
    @Query("{ 'date': { $gte: ?0, $lte: ?1 } }")
    List<DailyFoodLog> findByDateRange(LocalDate startDate, LocalDate endDate);

    @Query("{ 'userId': ?0, 'date': { $gte: ?1, $lte: ?2 } }")
    List<DailyFoodLog> findByUserIdAndDateRange(String userId, LocalDate startDate, LocalDate endDate);

    /**
     * Find daily food logs by mealId (date string) range using string comparison.
     * Since mealId is the date string "YYYY-MM-DD", lexicographic comparison works.
     */
    @Query("{ 'mealId': { $gte: ?0, $lte: ?1 } }")
    List<DailyFoodLog> findByMealIdRange(String startMealId, String endMealId);

    @Query("{ 'userId': ?0, 'dateString': { $gte: ?1, $lte: ?2 } }")
    List<DailyFoodLog> findByUserIdAndDateStringRange(String userId, String startDateString, String endDateString);
}
