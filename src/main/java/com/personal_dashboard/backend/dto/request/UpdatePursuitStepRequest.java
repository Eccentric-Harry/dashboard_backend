package com.personal_dashboard.backend.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Partial update of one step: every field is optional and a null field is left
 * unchanged. The original {@code {"text": "..."}} body still works.
 */
@Data
public class UpdatePursuitStepRequest {

    @Size(max = 200, message = "Step text must be at most 200 characters")
    private String text;

    /** 0 clears the estimate. */
    @Min(value = 0, message = "Estimate cannot be negative")
    @Max(value = 600, message = "Estimate must be 600 minutes or fewer")
    private Integer estimateMinutes;

    /** Blank clears the note. */
    @Size(max = 300, message = "Resume note must be at most 300 characters")
    private String resumeNote;

    /** Blank clears the takeaways. */
    @Size(max = 4000, message = "Takeaways must be at most 4000 characters")
    private String takeaways;
}
