package com.personal_dashboard.backend.repository;

import com.personal_dashboard.backend.model.Goal;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface GoalRepository extends MongoRepository<Goal, String> {

    List<Goal> findByUserIdOrderByOrderAsc(String userId);

    List<Goal> findByUserIdAndStatusOrderByOrderAsc(String userId, String status);

    long countByUserIdAndStatus(String userId, String status);

    Optional<Goal> findByIdAndUserId(String id, String userId);
}
