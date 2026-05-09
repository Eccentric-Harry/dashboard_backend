package com.personal_dashboard.backend.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDate;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "daily_logs")
public class DailyLog {

    @Id
    private String id;

    private LocalDate date;

    private Integer githubCommits;

    private Integer leetCodeSolved;

    private List<String> newLearnings;

    private String dailyOneThing;

    private Boolean dailyOneThingCompleted;

    private String moodRating;
}
