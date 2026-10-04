package com.personal_dashboard.backend.repository;

import com.personal_dashboard.backend.model.GoalKit;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface GoalKitRepository extends MongoRepository<GoalKit, String> {

    Optional<GoalKit> findByUserIdAndGoalId(String userId, String goalId);

    void deleteByUserIdAndGoalId(String userId, String goalId);
}
