package com.personal_dashboard.backend.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Triage a mind entry — Park (status=PARKED + reviewDate), Release (status=RELEASED),
 * Reframe (status=RESOLVED + reframedText + distortionTag), or bring a parked worry
 * back (status=OPEN). Fields other than status are optional and only applied when present.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MindStatusRequest {

    @NotBlank(message = "Status is required")
    private String status;

    @Pattern(regexp = "\\d{4}-\\d{2}-\\d{2}", message = "Review date must be in format YYYY-MM-DD")
    private String reviewDate;

    private String reframedText;

    private String distortionTag;

    private Boolean pinned;
}
