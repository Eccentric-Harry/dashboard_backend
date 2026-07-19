package com.personal_dashboard.backend.repository;

import com.personal_dashboard.backend.model.StreakState;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface StreakStateRepository extends MongoRepository<StreakState, String> {

    Optional<StreakState> findByUserId(String userId);
}
