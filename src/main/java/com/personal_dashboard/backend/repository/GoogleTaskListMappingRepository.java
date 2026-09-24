package com.personal_dashboard.backend.repository;

import com.personal_dashboard.backend.model.GoogleTaskListMapping;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface GoogleTaskListMappingRepository extends MongoRepository<GoogleTaskListMapping, String> {

    List<GoogleTaskListMapping> findByUserIdAndAccountEmail(String userId, String accountEmail);

    Optional<GoogleTaskListMapping> findByUserIdAndAccountEmailAndGoogleTaskListId(
            String userId, String accountEmail, String googleTaskListId);

    void deleteByUserIdAndAccountEmail(String userId, String accountEmail);
}
