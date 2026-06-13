package com.personal_dashboard.backend.repository;

import com.personal_dashboard.backend.model.Prompt;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface PromptRepository extends MongoRepository<Prompt, String> {
}
