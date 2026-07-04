package com.personal_dashboard.backend.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "daily_tasks")
public class DailyTask implements UserOwnedDocument {

    @Id
    private String id;

    private String userId;

    private String title;
    private LocalDate date;
    private String scheduledTime;
    private String startTime;
    private String endTime;

    @Builder.Default
    private Boolean allDay = true;

    @Builder.Default
    private String itemType = "TASK";

    @Builder.Default
    private String category = "Personal";

    private String color;
    private String notes;

    @Builder.Default
    private Boolean completed = false;

    @Builder.Default
    private Boolean cancelled = false;

    @Builder.Default
    private String status = "TODO"; // TODO, IN_PROGRESS, DONE

    private LocalDateTime completedAt;

    @Builder.Default
    private Integer sortOrder = 0;

    @Builder.Default
    private String recurrenceFrequency = "NONE";

    private LocalDate recurrenceUntil;


    private List<LocalDate> completedDates;
    private List<LocalDate> cancelledDates;
    private List<LocalDate> excludedDates;

    private List<SubTask> subtasks;
    private List<String> tags;
    private List<TaskHistoryEvent> history;

    @org.springframework.data.mongodb.core.index.Indexed(sparse = true)
    private String googleEventId;

    private Instant lastSyncedAt;

    @org.springframework.data.annotation.Version
    private Long versionToken;

    @CreatedDate
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;
}
