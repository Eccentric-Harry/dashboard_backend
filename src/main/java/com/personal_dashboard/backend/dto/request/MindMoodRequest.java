package com.personal_dashboard.backend.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A 1–5 mood check-in for a given day. Persisted onto the day's DailyLog.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MindMoodRequest {

    @Min(value = 1, message = "Mood score must be between 1 and 5")
    @Max(value = 5, message = "Mood score must be between 1 and 5")
    private Integer moodScore;

    private String moodNote;
}
