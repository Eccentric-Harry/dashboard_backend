package com.personal_dashboard.backend.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A manual "I moved" log for the MOVE ring — the escape hatch for walks and
 * gym sessions that never reach Strava. One log per day; logging again replaces it.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ManualMoveRequest {

    @NotBlank(message = "Date is required")
    @Pattern(regexp = "\\d{4}-\\d{2}-\\d{2}", message = "Date must be in format YYYY-MM-DD")
    private String date;

    @NotNull(message = "Minutes are required")
    @Min(value = 1, message = "Minutes must be at least 1")
    @Max(value = 1440, message = "Minutes cannot exceed a day")
    private Integer minutes;

    // Walk | Run | Gym | Cycle | Other — free string, mirrors the TS union.
    private String activityType;

    private String note;
}
