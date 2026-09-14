package com.personal_dashboard.backend.dto.request;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonCreator;
import jakarta.validation.Valid;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * One step of a pursuit as sent on create — recursive, so a step can carry sub-steps
 * (the service caps the depth). A bare JSON string is still accepted as a leaf step,
 * which keeps the original flat {@code "steps": ["a", "b"]} payload working.
 */
@Data
@NoArgsConstructor
public class PursuitStepInput {

    private String text;

    private String note;

    /** Planned minutes for a leaf step; clamped server-side, ignored on parents. */
    private Integer estimateMinutes;

    @Valid
    @JsonAlias({"steps", "subSteps"})
    private List<PursuitStepInput> children;

    // DELEGATING is explicit: with parameter names compiled in, a lone "text" argument
    // would otherwise be read as a properties creator and bare strings would be rejected.
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public PursuitStepInput(String text) {
        this.text = text;
    }
}
