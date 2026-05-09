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
public class RunSessionRequest {

    @NotNull(message = "Distance in km is required")
    @DecimalMin(value = "0.1", message = "Distance must be at least 0.1 km")
    @DecimalMax(value = "100.0", message = "Distance must be less than 100 km")
    private Double distanceKm;

    @NotNull(message = "Duration in minutes is required")
    @Min(value = 1, message = "Duration must be at least 1 minute")
    @Max(value = 1440, message = "Duration must be less than 24 hours (1440 minutes)")
    private Integer durationMinutes;

    @NotNull(message = "Elevation gain in meters is required")
    @Min(value = 0, message = "Elevation gain must be non-negative")
    @Max(value = 10000, message = "Elevation gain must be less than 10000 meters")
    private Integer elevationGainMeters;

    @NotBlank(message = "Date is required")
    @Pattern(regexp = "\\d{4}-\\d{2}-\\d{2}", message = "Date must be in format YYYY-MM-DD")
    private String date;
}
