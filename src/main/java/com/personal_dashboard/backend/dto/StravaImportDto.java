package com.personal_dashboard.backend.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StravaImportDto {

    private Long id;

    private String name;

    @JsonProperty("sport_type")
    private String sportType;

    @JsonProperty("start_time")
    private String startTime;

    @JsonProperty("distance_raw")
    private Double distanceRaw;

    @JsonProperty("moving_time_raw")
    private Integer movingTimeRaw;

    @JsonProperty("elevation_gain_raw")
    private Double elevationGainRaw;

    @JsonProperty("activity_url")
    private String activityUrl;
}
