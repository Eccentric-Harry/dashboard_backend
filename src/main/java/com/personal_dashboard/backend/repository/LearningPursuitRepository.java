package com.personal_dashboard.backend.repository;

import com.personal_dashboard.backend.model.LearningPursuit;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface LearningPursuitRepository extends MongoRepository<LearningPursuit, String> {
}
