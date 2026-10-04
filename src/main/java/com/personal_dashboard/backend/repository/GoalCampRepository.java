package com.personal_dashboard.backend.repository;

import com.personal_dashboard.backend.model.GoalCamp;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface GoalCampRepository extends MongoRepository<GoalCamp, String> {

    Optional<GoalCamp> findByUserId(String userId);
}
