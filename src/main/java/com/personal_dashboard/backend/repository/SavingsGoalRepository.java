package com.personal_dashboard.backend.repository;

import com.personal_dashboard.backend.model.SavingsGoal;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SavingsGoalRepository extends MongoRepository<SavingsGoal, String> {

    List<SavingsGoal> findByUserId(String userId);

    long countByUserIdAndStatusIn(String userId, List<String> statuses);
}
