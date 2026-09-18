package com.personal_dashboard.backend.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

@Data
public class UpdateProteinTargetRequest {

    /** Grams per day. Null clears it, returning to the calculated 2 g/kg target. */
    @Min(value = 20, message = "Protein target must be at least 20 g")
    @Max(value = 400, message = "Protein target must be at most 400 g")
    private Integer grams;
}
