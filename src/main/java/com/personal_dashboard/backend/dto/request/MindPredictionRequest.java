package com.personal_dashboard.backend.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Attach a prediction to a worry at park time: what is feared, and how likely it feels.
 * Both fields optional — skipping them still parks the worry exactly as before.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MindPredictionRequest {

    private String fearedOutcome;

    @Min(value = 0, message = "Probability must be between 0 and 100")
    @Max(value = 100, message = "Probability must be between 0 and 100")
    private Integer predictedProbability;
}
