package com.personal_dashboard.backend.repository;

import com.personal_dashboard.backend.model.FocusSession;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface FocusSessionRepository extends MongoRepository<FocusSession, String> {

    Optional<FocusSession> findTopByUserIdAndStatusNotOrderByStartTimeDesc(String userId, com.personal_dashboard.backend.model.FocusSessionStatus status);

    Optional<FocusSession> findTopByStatusNotOrderByStartTimeDesc(com.personal_dashboard.backend.model.FocusSessionStatus status);
}
