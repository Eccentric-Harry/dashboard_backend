package com.personal_dashboard.backend.dto.request;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GoalCheckInRequest {

    /** The local day the progress belongs to, yyyy-MM-dd. */
    @NotBlank(message = "date is required")
    @Pattern(regexp = "\\d{4}-\\d{2}-\\d{2}", message = "date must be yyyy-MM-dd")
    private String date;

    /** COUNT goals only; CHECK goals always record 1. */
    @Positive(message = "value must be above zero")
    @DecimalMax(value = "100000", message = "value is too large")
    private Double value;

    @Size(max = 200, message = "note must be at most 200 characters")
    private String note;

    /** Optional guided-session key (see GoalCheckIn.session). */
    @Pattern(regexp = "^[a-z0-9-]{0,40}$", message = "session must be a short lowercase key")
    private String session;

    /** Optional minutes, for sessions and timed practices. */
    @jakarta.validation.constraints.Min(value = 1, message = "minutes must be at least 1")
    @jakarta.validation.constraints.Max(value = 240, message = "minutes must be at most 240")
    private Integer minutes;

    /** Optional practice kind (see GoalCheckIn.practice). */
    @Pattern(regexp = "^(breathe|walk|write|still|gratitude|nature|talk|learn|other)?$", message = "practice must be a known practice")
    private String practice;
}
