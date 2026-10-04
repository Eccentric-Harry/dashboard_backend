package com.personal_dashboard.backend.repository;

import com.personal_dashboard.backend.model.ProgramLog;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface ProgramLogRepository extends MongoRepository<ProgramLog, String> {

    List<ProgramLog> findByUserIdAndProgramIdOrderByDateAscCreatedAtAsc(String userId, String programId);

    Optional<ProgramLog> findByIdAndUserIdAndProgramId(String id, String userId, String programId);

    void deleteByUserIdAndProgramId(String userId, String programId);
}
