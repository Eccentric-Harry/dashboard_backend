package com.personal_dashboard.backend.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import java.util.List;

@Data
public class PursuitRequest {

    @NotBlank(message = "Title is required")
    private String title;

    @NotBlank(message = "Category is required")
    private String category;

    /** Optional; null leaves an existing goal unchanged on update. */
    @Size(max = 300, message = "Goal must be at most 300 characters")
    private String goal;

    /** Only read on create; nested up to LearningPursuitService.MAX_DEPTH levels. */
    @Valid
    private List<PursuitStepInput> steps;
}
