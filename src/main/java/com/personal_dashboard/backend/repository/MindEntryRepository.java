package com.personal_dashboard.backend.repository;

import com.personal_dashboard.backend.model.MindEntry;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface MindEntryRepository extends MongoRepository<MindEntry, String> {

    List<MindEntry> findByUserId(String userId);

    Optional<MindEntry> findByIdAndUserId(String id, String userId);

    List<MindEntry> findByUserIdAndType(String userId, String type);

    List<MindEntry> findByUserIdAndStatus(String userId, String status);

    @Query("{ 'userId': ?0, 'date': { $gte: ?1, $lte: ?2 } }")
    List<MindEntry> findByUserIdAndDateRange(String userId, LocalDate startDate, LocalDate endDate);

    // Parked worries whose review date has arrived — used to resurface them into the inbox.
    @Query("{ 'userId': ?0, 'status': 'PARKED', 'reviewDate': { $lte: ?1 } }")
    List<MindEntry> findDueParked(String userId, LocalDate onOrBefore);
}
