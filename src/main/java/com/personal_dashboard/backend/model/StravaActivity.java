package com.personal_dashboard.backend.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDate;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "strava_activities")
public class StravaActivity {

    @Id
    private String id;

    private String stravaEmbedId;

    private LocalDate date;

    private String activityName;

    private String sportType;

    private Double distanceKm;

    private String movingTime;

    private Double movingTimeMinutes;

    private Integer elevationGainMeters;

    private Double paceMinPerKm;

    private String source;
}
