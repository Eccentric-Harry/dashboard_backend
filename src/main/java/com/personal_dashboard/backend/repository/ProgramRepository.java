package com.personal_dashboard.backend.repository;

import com.personal_dashboard.backend.model.Program;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface ProgramRepository extends MongoRepository<Program, String> {

    Optional<Program> findByIdAndUserId(String id, String userId);

    Optional<Program> findFirstByUserIdAndStatusOrderByStartDateDesc(String userId, String status);

    List<Program> findByUserIdOrderByStartDateDesc(String userId);
}
