package com.personal_dashboard.backend.repository;

import com.personal_dashboard.backend.model.MealAnalysisJob;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface MealAnalysisJobRepository extends MongoRepository<MealAnalysisJob, String> {

    /** Scope lookups to the owner so one user can never poll another's job. */
    Optional<MealAnalysisJob> findByIdAndUserId(String id, String userId);
}
