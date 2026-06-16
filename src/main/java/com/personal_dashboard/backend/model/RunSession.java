package com.personal_dashboard.backend.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
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
@Document(collection = "run_sessions")
public class RunSession {

    @Id
    private String id;

    private String title;

    private LocalDate date;

    private Double distanceKm;

    private String movingTime;

    private String averagePace;

    private Integer elevationMeters;

    private List<RunSplit> splits;

    private List<ElevationPoint> elevationProfile;

    @CreatedDate
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;
}
