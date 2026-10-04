package com.personal_dashboard.backend.repository;

import com.personal_dashboard.backend.model.ProgramAssessment;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface ProgramAssessmentRepository extends MongoRepository<ProgramAssessment, String> {

    List<ProgramAssessment> findByUserIdAndProgramIdOrderByDateAscCreatedAtAsc(String userId, String programId);

    Optional<ProgramAssessment> findByIdAndUserIdAndProgramId(String id, String userId, String programId);

    void deleteByUserIdAndProgramId(String userId, String programId);
}
