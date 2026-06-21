package com.personal_dashboard.backend.repository;

import com.personal_dashboard.backend.model.Prompt;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PromptRepository extends MongoRepository<Prompt, String> {
    List<Prompt> findByUserId(String userId, Sort sort);

    Optional<Prompt> findByIdAndUserId(String id, String userId);
}
