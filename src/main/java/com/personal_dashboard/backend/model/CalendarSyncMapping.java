package com.personal_dashboard.backend.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * Tracks which local DailyTask has been synced to which Google Calendar account.
 * Replaces the flat googleEventId field on DailyTask, enabling multi-account sync.
 *
 * _id is a composite: taskId + ":" + calendarEmail
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "calendar_sync_mappings")
@CompoundIndexes({
    @CompoundIndex(def = "{'googleEventId': 1, 'userId': 1}"),
    @CompoundIndex(def = "{'taskId': 1, 'calendarEmail': 1}", unique = true)
})
public class CalendarSyncMapping {

    @Id
    private String id; // taskId + ":" + calendarEmail

    @Indexed
    private String taskId;

    @Indexed
    private String userId;

    private String calendarEmail;

    private String googleEventId;

    private Instant lastSyncedAt;

    public static String compositeId(String taskId, String calendarEmail) {
        return taskId + ":" + calendarEmail;
    }
}
