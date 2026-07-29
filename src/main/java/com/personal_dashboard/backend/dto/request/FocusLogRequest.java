package com.personal_dashboard.backend.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Record focus work that happened away from the app — the office-work case the
 * live timer structurally cannot capture.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FocusLogRequest {

    @NotNull(message = "Date is required")
    @Pattern(regexp = "\\d{4}-\\d{2}-\\d{2}", message = "Date must be in format YYYY-MM-DD")
    private String date;

    /** Capped at a waking day: anything above it is a typo, not a work session. */
    @NotNull(message = "Minutes are required")
    @Min(value = 1, message = "Minutes must be at least 1")
    @Max(value = 960, message = "Minutes must be 960 (16h) or fewer")
    private Integer minutes;

    /** Optional start clock time (HH:mm) — placed at midday when absent. */
    @Pattern(regexp = "([01]\\d|2[0-3]):[0-5]\\d", message = "Start time must be HH:mm")
    private String startTime;

    @Size(max = 120, message = "Pursuit must be 120 characters or fewer")
    private String activePursuit;

    @Size(max = 280, message = "Note must be 280 characters or fewer")
    private String note;
}
