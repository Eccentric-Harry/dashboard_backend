package com.personal_dashboard.backend.repository;

import com.personal_dashboard.backend.model.DailyTask;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface DailyTaskRepository extends MongoRepository<DailyTask, String> {

    List<DailyTask> findByUserId(String userId);

    java.util.Optional<DailyTask> findByIdAndUserId(String id, String userId);

    java.util.Optional<DailyTask> findByGoogleEventIdAndUserId(String googleEventId, String userId);

    /**
     * Inclusive start, exclusive end (end = last day + 1). Works for BSON Date fields;
     * derived Between omits the last day and same-day ranges return nothing.
     */
    @Query("{ 'date': { $gte: ?0, $lt: ?1 } }")
    List<DailyTask> findByDateRange(LocalDate startInclusive, LocalDate endExclusive);

    @Query("{ 'userId': ?0, 'date': { $gte: ?1, $lt: ?2 } }")
    List<DailyTask> findByUserIdAndDateRange(String userId, LocalDate startInclusive, LocalDate endExclusive);

    @Query("{ $or: [ " +
            "{ 'date': { $gte: ?0, $lt: ?1 } }, " +
            "{ 'recurrenceFrequency': { $in: ['DAILY', 'WEEKLY', 'MONTHLY'] }, 'date': { $lt: ?1 }, $or: [ { 'recurrenceUntil': null }, { 'recurrenceUntil': { $gte: ?0 } } ] } " +
            "] }")
    List<DailyTask> findCalendarCandidates(LocalDate startInclusive, LocalDate endExclusive);

    @Query("{ 'userId': ?0, $or: [ " +
            "{ 'date': { $gte: ?1, $lt: ?2 } }, " +
            "{ 'recurrenceFrequency': { $in: ['DAILY', 'WEEKLY', 'MONTHLY'] }, 'date': { $lt: ?2 }, $or: [ { 'recurrenceUntil': null }, { 'recurrenceUntil': { $gte: ?1 } } ] } " +
            "] }")
    List<DailyTask> findCalendarCandidates(String userId, LocalDate startInclusive, LocalDate endExclusive);

    /**
     * Fetches tasks scheduled on the target date (inclusive/exclusive range) OR scheduled before the target date
     * that are not yet marked as completed OR were completed on or after the specified threshold.
     */
    @Query("{ $or: [ { 'date': { $gte: ?0, $lt: ?1 } }, { 'date': { $lt: ?0 }, 'completed': { $ne: true } } ] }")
    List<DailyTask> findTasksForDateWithIncompletePrevious(LocalDate dateInclusive, LocalDate nextDayExclusive);

    @Query("{ 'userId': ?0, $or: [ { 'date': { $gte: ?1, $lt: ?2 } }, { 'date': { $lt: ?1 }, 'completed': { $ne: true } } ] }")
    List<DailyTask> findTasksForDateWithIncompletePrevious(String userId, LocalDate dateInclusive, LocalDate nextDayExclusive);

    /**
     * Fetches tasks that are not yet completed OR completed but completed after the given threshold.
     */
    @Query("{ $or: [ { 'completed': { $ne: true } }, { 'completed': true, 'completedAt': { $gte: ?0 } } ] }")
    List<DailyTask> findActiveTasks(LocalDateTime completedAtThreshold);

    @Query("{ 'userId': ?0, $or: [ { 'completed': { $ne: true } }, { 'completed': true, 'completedAt': { $gte: ?1 } } ] }")
    List<DailyTask> findActiveTasks(String userId, LocalDateTime completedAtThreshold);
}
