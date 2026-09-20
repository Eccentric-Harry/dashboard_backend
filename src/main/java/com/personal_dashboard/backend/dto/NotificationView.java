package com.personal_dashboard.backend.dto;

import com.personal_dashboard.backend.model.NotificationStatus;
import com.personal_dashboard.backend.model.ScheduledNotification;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.time.LocalDate;

/**
 * The client-facing projection of a notification. Deliberately narrower than the stored
 * record: delivery endpoints and the snooze capability token are server-side concerns and
 * never travel to the notification centre.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NotificationView {

    private String id;
    private String title;
    private String body;
    private String url;
    private String sourceType;
    private String sourceId;
    private String itemType;
    private LocalDate occurrenceDate;
    private Instant fireAt;
    private NotificationStatus status;
    private Instant sentAt;
    private Instant acknowledgedAt;
    private Instant readAt;

    public static NotificationView from(ScheduledNotification row) {
        return NotificationView.builder()
                .id(row.getId())
                .title(row.getTitle())
                .body(row.getBody())
                .url(row.getUrl())
                .sourceType(row.getSourceType())
                .sourceId(row.getSourceId())
                .itemType(row.getItemType())
                .occurrenceDate(row.getOccurrenceDate())
                .fireAt(row.getFireAt())
                .status(row.getStatus())
                .sentAt(row.getSentAt())
                .acknowledgedAt(row.getAcknowledgedAt())
                .readAt(row.getReadAt())
                .build();
    }
}
