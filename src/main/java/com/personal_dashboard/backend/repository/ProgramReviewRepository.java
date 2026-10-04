package com.personal_dashboard.backend.repository;

import com.personal_dashboard.backend.model.ProgramReview;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface ProgramReviewRepository extends MongoRepository<ProgramReview, String> {

    List<ProgramReview> findByUserIdAndProgramIdOrderByWeekStartAsc(String userId, String programId);

    Optional<ProgramReview> findByUserIdAndProgramIdAndWeekStart(String userId, String programId, LocalDate weekStart);

    void deleteByUserIdAndProgramId(String userId, String programId);
}
