package com.personal_dashboard.backend.repository;

import com.personal_dashboard.backend.model.CalendarSyncMapping;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CalendarSyncMappingRepository extends MongoRepository<CalendarSyncMapping, String> {

    Optional<CalendarSyncMapping> findByGoogleEventIdAndUserId(String googleEventId, String userId);

    List<CalendarSyncMapping> findByTaskId(String taskId);

    List<CalendarSyncMapping> findByUserId(String userId);

    List<CalendarSyncMapping> findByUserIdAndCalendarEmail(String userId, String calendarEmail);

    void deleteByUserId(String userId);

    void deleteByUserIdAndCalendarEmail(String userId, String calendarEmail);

    void deleteByTaskId(String taskId);
}
