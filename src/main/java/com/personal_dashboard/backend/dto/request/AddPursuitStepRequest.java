package com.personal_dashboard.backend.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class AddPursuitStepRequest {

    /** Step to nest the new step under; null appends a top-level step. */
    private String parentId;

    @NotBlank(message = "Step text is required")
    @Size(max = 200, message = "Step text must be at most 200 characters")
    private String text;
}
