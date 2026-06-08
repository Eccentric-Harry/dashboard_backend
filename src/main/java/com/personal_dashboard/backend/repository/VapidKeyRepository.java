package com.personal_dashboard.backend.repository;

import com.personal_dashboard.backend.model.VapidKey;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface VapidKeyRepository extends MongoRepository<VapidKey, String> {
}
