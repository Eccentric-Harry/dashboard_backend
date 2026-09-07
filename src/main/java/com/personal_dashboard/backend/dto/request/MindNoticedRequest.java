package com.personal_dashboard.backend.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Log an intrusive thought as noticed and let pass.
 *
 * Every field is optional, including the text — the primary path is a single tap with
 * an empty body. That is deliberate: writing an intrusive thought out in detail is
 * itself a mental compulsion, so the app counts these rather than collecting them.
 * When text is supplied it is sealed on the entry and never returned by a normal read.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MindNoticedRequest {

    private String text;

    // DOUBT | HARM | IMMORAL | UNNAMED — defaults to UNNAMED.
    private String category;

    @Min(value = 1, message = "Intensity must be between 1 and 5")
    @Max(value = 5, message = "Intensity must be between 1 and 5")
    private Integer intensity;

    /** Seconds waited without checking, when the urge-decay timer was used. */
    @Min(value = 0, message = "Wait time cannot be negative")
    private Integer urgeWaitedSeconds;

    private Boolean urgeFaded;

    @Pattern(regexp = "\\d{4}-\\d{2}-\\d{2}", message = "Date must be in format YYYY-MM-DD")
    private String date;
}
