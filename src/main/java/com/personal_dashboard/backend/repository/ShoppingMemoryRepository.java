package com.personal_dashboard.backend.repository;

import com.personal_dashboard.backend.model.ShoppingMemory;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ShoppingMemoryRepository extends MongoRepository<ShoppingMemory, String> {

    Optional<ShoppingMemory> findFirstByUserId(String userId);
}
