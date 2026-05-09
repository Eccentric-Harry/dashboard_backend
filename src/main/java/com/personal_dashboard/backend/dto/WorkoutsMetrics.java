package com.personal_dashboard.backend.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkoutsMetrics {
    private AthleteProfile athlete;
    private ActivityStats stats;
    private RecentActivities recent;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AthleteProfile {
        private Long id;
        private String name;
        private String location;
        private String profileImage;
        private Boolean premium;
        @JsonProperty("follower_count")
        private Integer followerCount;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ActivityStats {
        @JsonProperty("recent_runs")
        private ActivityTotals recentRuns;
        @JsonProperty("recent_rides")
        private ActivityTotals recentRides;
        @JsonProperty("recent_swims")
        private ActivityTotals recentSwims;
        @JsonProperty("ytd_runs")
        private ActivityTotals ytdRuns;
        @JsonProperty("ytd_rides")
        private ActivityTotals ytdRides;
        @JsonProperty("ytd_swims")
        private ActivityTotals ytdSwims;
        @JsonProperty("biggest_ride_distance")
        private Double biggestRideDistance;
        @JsonProperty("biggest_climb_elevation")
        private Double biggestClimbElevation;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ActivityTotals {
        private Integer count;
        private Double distance;
        @JsonProperty("moving_time_hours")
        private Double movingTimeHours;
        @JsonProperty("elevation_gain")
        private Double elevationGain;
        @JsonProperty("achievement_count")
        private Integer achievementCount;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RecentActivities {
        private List<ActivitySummary> activities;
        private Integer count;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ActivitySummary {
        private Long id;
        private String name;
        private String type;
        @JsonProperty("sport_type")
        private String sportType;
        private Double distance;
        @JsonProperty("moving_time_minutes")
        private Integer movingTimeMinutes;
        @JsonProperty("elevation_gain")
        private Double elevationGain;
        @JsonProperty("start_date")
        private String startDate;
        @JsonProperty("average_speed")
        private Double averageSpeed;
        @JsonProperty("average_heartrate")
        private Double averageHeartrate;
        @JsonProperty("kudos_count")
        private Integer kudosCount;
    }
}
