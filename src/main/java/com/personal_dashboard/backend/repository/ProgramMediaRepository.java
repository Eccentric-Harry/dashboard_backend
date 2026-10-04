package com.personal_dashboard.backend.repository;

import com.personal_dashboard.backend.model.ProgramMedia;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

/** Listings go through MongoTemplate with {@code data} projected away (ProgramService#listMedia). */
public interface ProgramMediaRepository extends MongoRepository<ProgramMedia, String> {

    Optional<ProgramMedia> findByIdAndUserIdAndProgramId(String id, String userId, String programId);

    long countByUserIdAndProgramId(String userId, String programId);

    void deleteByUserIdAndProgramId(String userId, String programId);
}
