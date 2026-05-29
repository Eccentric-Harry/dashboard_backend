package com.personal_dashboard.backend.repository;

import com.personal_dashboard.backend.model.DailyTask;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface DailyTaskRepository extends MongoRepository<DailyTask, String> {

    /**
     * Inclusive start, exclusive end (end = last day + 1). Works for BSON Date fields;
     * derived Between omits the last day and same-day ranges return nothing.
     */
    @Query("{ 'date': { $gte: ?0, $lt: ?1 } }")
    List<DailyTask> findByDateRange(LocalDate startInclusive, LocalDate endExclusive);

    /**
     * Fetches tasks scheduled on the target date (inclusive/exclusive range) OR scheduled before the target date
     * that are not yet marked as completed OR were completed on or after the specified threshold.
     */
    @Query("{ $or: [ { 'date': { $gte: ?0, $lt: ?1 } }, { 'date': { $lt: ?0 }, 'completed': { $ne: true } }, { 'date': { $lt: ?0 }, 'completed': true, 'completedAt': { $gte: ?2 } } ] }")
    List<DailyTask> findTasksForDateWithIncompletePrevious(LocalDate dateInclusive, LocalDate nextDayExclusive, java.time.LocalDateTime completedAfterThreshold);
}
