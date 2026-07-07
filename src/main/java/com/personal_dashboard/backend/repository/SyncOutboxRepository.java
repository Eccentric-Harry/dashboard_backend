package com.personal_dashboard.backend.repository;

import com.personal_dashboard.backend.model.SyncOutboxEntry;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface SyncOutboxRepository extends MongoRepository<SyncOutboxEntry, String> {

    /** Due work: PENDING entries whose nextAttemptAt has passed. */
    List<SyncOutboxEntry> findByStatusAndNextAttemptAtLessThanEqual(String status, Instant now);

    /** Used to coalesce repeated changes to the same task into one PENDING entry. */
    Optional<SyncOutboxEntry> findFirstByTaskIdAndStatus(String taskId, String status);
}
