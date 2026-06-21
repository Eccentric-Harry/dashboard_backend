package com.personal_dashboard.backend.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDate;
import java.util.List;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "daily_logs")
@CompoundIndex(name = "user_date_unique", def = "{'userId': 1, 'dateString': 1}", unique = true)
public class DailyLog implements UserOwnedDocument {

    @Id
    private String id;

    private String userId;

    private String dateString;

    private LocalDate date;

    private List<String> newLearnings;

    private String moodRating;

    @CreatedDate
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;
}
