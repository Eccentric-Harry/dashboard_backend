package com.personal_dashboard.backend.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Record what actually happened to a parked worry once its review date arrives.
 * Severity is only meaningful when the outcome was PARTLY or HAPPENED.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MindVerdictRequest {

    // NOT_HAPPENED | PARTLY | HAPPENED
    @NotBlank(message = "Outcome is required")
    private String outcome;

    // BETTER | AS_FEARED | WORSE
    private String severity;
}
