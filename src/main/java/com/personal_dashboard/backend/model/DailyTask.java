package com.personal_dashboard.backend.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDate;
import java.time.LocalDateTime;

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
    private String notes;

    @Builder.Default
    private Boolean completed = false;

    @Builder.Default
    private Integer sortOrder = 0;

    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();
}
