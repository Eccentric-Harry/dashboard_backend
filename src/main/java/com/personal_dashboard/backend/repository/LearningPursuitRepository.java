package com.personal_dashboard.backend.repository;

import com.personal_dashboard.backend.model.LearningPursuit;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface LearningPursuitRepository extends MongoRepository<LearningPursuit, String> {
    List<LearningPursuit> findByUserId(String userId);

    Optional<LearningPursuit> findByIdAndUserId(String id, String userId);

    long countByUserId(String userId);
}

