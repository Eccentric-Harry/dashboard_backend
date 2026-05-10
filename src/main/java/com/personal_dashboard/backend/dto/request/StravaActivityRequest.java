package com.personal_dashboard.backend.dto.request;

import jakarta.validation.constraints.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StravaActivityRequest {

    @NotBlank(message = "Activity name is required")
    private String activityName;

    @NotBlank(message = "Sport type is required")
    private String sportType;

    @NotNull(message = "Distance in km is required")
    @DecimalMin(value = "0.01", message = "Distance must be at least 0.01 km")
    @DecimalMax(value = "500.0", message = "Distance must be less than 500 km")
    private Double distanceKm;

    @NotBlank(message = "Moving time is required (e.g., '26:32' or '2:21:52')")
    private String movingTime;

    @NotNull(message = "Elevation gain in meters is required")
    @Min(value = 0, message = "Elevation gain must be non-negative")
    @Max(value = 10000, message = "Elevation gain must be less than 10000 meters")
    private Integer elevationGainMeters;

    @NotBlank(message = "Date is required")
    @Pattern(regexp = "\\d{4}-\\d{2}-\\d{2}", message = "Date must be in format YYYY-MM-DD")
    private String date;

    private String stravaEmbedId;
}
