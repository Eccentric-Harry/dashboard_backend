package com.personal_dashboard.backend.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Record a Spiral Breaker session. Logged on completion or abandonment alike — an
 * abandoned run is still useful signal, and nothing here should feel like a test the
 * user can fail.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MindSpiralRequest {

    /** Answer to the Worry-Tree question: is this solvable in the next 24 hours? */
    private Boolean solvableIn24h;

    /** DailyTask the user chose to return to, if any. */
    private String returnedToTaskId;

    @Min(value = 0, message = "Duration cannot be negative")
    private Integer durationSeconds;

    @Pattern(regexp = "\\d{4}-\\d{2}-\\d{2}", message = "Date must be in format YYYY-MM-DD")
    private String date;
}
