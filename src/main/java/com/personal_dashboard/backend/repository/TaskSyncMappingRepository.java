package com.personal_dashboard.backend.repository;

import com.personal_dashboard.backend.model.TaskSyncMapping;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TaskSyncMappingRepository extends MongoRepository<TaskSyncMapping, String> {

    /** Account-agnostic lookup — resolves a polled Google task back to its local row. */
    Optional<TaskSyncMapping> findByGoogleTaskIdAndUserId(String googleTaskId, String userId);

    List<TaskSyncMapping> findByTaskId(String taskId);

    List<TaskSyncMapping> findByUserIdAndAccountEmail(String userId, String accountEmail);

    long countByUserIdAndAccountEmail(String userId, String accountEmail);

    void deleteByUserIdAndAccountEmail(String userId, String accountEmail);
}
