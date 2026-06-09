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

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "daily_tasks")
public class DailyTask {

    @Id
    private String id;

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

    private LocalDateTime completedAt;

    @Builder.Default
    private Integer sortOrder = 0;

    @Builder.Default
    private String recurrenceFrequency = "NONE";

    private LocalDate recurrenceUntil;

    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    private List<LocalDate> completedDates;
    private List<LocalDate> excludedDates;
}
