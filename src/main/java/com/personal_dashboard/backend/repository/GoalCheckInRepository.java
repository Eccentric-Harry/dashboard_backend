package com.personal_dashboard.backend.repository;

import com.personal_dashboard.backend.model.GoalCheckIn;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface GoalCheckInRepository extends MongoRepository<GoalCheckIn, String> {

    /** Every check-in the user has — the board judges lifetime weeks, so it needs them all. */
    List<GoalCheckIn> findByUserId(String userId);

    List<GoalCheckIn> findByUserIdAndGoalId(String userId, String goalId);

    List<GoalCheckIn> findByUserIdAndGoalIdAndDate(String userId, String goalId, LocalDate date);

    Optional<GoalCheckIn> findByIdAndUserIdAndGoalId(String id, String userId, String goalId);

    void deleteByUserIdAndGoalId(String userId, String goalId);
}
