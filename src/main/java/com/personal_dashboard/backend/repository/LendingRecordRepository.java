package com.personal_dashboard.backend.repository;

import com.personal_dashboard.backend.model.LendingRecord;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface LendingRecordRepository extends MongoRepository<LendingRecord, String> {
    List<LendingRecord> findByUserId(String userId, Sort sort);

    Optional<LendingRecord> findByIdAndUserId(String id, String userId);
}
