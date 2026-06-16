package com.personal_dashboard.backend.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "focus_sessions")
public class FocusSession {

    @Id
    private String id;

    private String userId;

    private String activePursuit;

    private int durationMinutes;

    @Builder.Default
    private FocusSessionStatus status = FocusSessionStatus.IDLE;

    private Instant startTime;

    private Instant endTime;

    private Long remainingSecondsOnPause;

    @CreatedDate
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;
}
