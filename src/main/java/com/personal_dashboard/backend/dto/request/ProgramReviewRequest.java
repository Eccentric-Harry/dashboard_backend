package com.personal_dashboard.backend.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/** PUT /program/{id}/reviews/{weekStart} — the weekly review, and the only way targets change. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProgramReviewRequest {

    @NotNull(message = "selfTrust is required")
    @Min(value = 1, message = "selfTrust is 1–10")
    @Max(value = 10, message = "selfTrust is 1–10")
    private Integer selfTrust;

    @Size(max = 300, message = "win must be at most 300 characters")
    private String win;

    @Size(max = 300, message = "obstacle must be at most 300 characters")
    private String obstacle;

    @Size(max = 300, message = "adjustment must be at most 300 characters")
    private String adjustment;

    @Size(max = 300, message = "ifThen must be at most 300 characters")
    private String ifThen;

    /** Track key → its new target. Tracks left out keep theirs. */
    @Size(max = 8, message = "at most 8 targets")
    private Map<
            @Pattern(regexp = "^(run|lift|protein|learn|english|regard)$", message = "unknown track") String,
            @Valid @NotNull TargetEdit> targets;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TargetEdit {
        @DecimalMin(value = "0", message = "target can't be negative")
        @DecimalMax(value = "1000", message = "target is too large")
        private Double target;

        @DecimalMin(value = "0", message = "floor can't be negative")
        @DecimalMax(value = "1000", message = "floor is too large")
        private Double floor;
    }
}
