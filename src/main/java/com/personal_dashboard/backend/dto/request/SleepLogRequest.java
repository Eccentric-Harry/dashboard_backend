package com.personal_dashboard.backend.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SleepLogRequest {

    /** Wake-up date, ISO yyyy-MM-dd. */
    @NotBlank(message = "date is required")
    @Pattern(regexp = "\\d{4}-\\d{2}-\\d{2}", message = "date must be yyyy-MM-dd")
    private String date;

    @NotBlank(message = "bedtime is required")
    @Pattern(regexp = "([01]\\d|2[0-3]):[0-5]\\d", message = "bedtime must be HH:mm")
    private String bedtime;

    @NotBlank(message = "wakeTime is required")
    @Pattern(regexp = "([01]\\d|2[0-3]):[0-5]\\d", message = "wakeTime must be HH:mm")
    private String wakeTime;

    @Min(value = 1, message = "quality must be between 1 and 5")
    @Max(value = 5, message = "quality must be between 1 and 5")
    private Integer quality;

    @Size(max = 500, message = "note must be at most 500 characters")
    private String note;

    /** "manual" (default) or "wearable". */
    private String source;
}
