package com.personal_dashboard.backend.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Create or update a mind entry (a captured thought, win, or gratitude note).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MindEntryRequest {

    @NotBlank(message = "Text is required")
    private String text;

    // THOUGHT | WIN | GRATITUDE | AFFIRMATION | REFLECTION | INTENTION — defaults to THOUGHT.
    private String type;

    private String valueTag;

    private Boolean pinned;

    @Pattern(regexp = "\\d{4}-\\d{2}-\\d{2}", message = "Date must be in format YYYY-MM-DD")
    private String date;
}
