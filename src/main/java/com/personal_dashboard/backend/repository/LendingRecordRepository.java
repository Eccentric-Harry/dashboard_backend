package com.personal_dashboard.backend.repository;

import com.personal_dashboard.backend.model.LendingRecord;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface LendingRecordRepository extends MongoRepository<LendingRecord, String> {
}
