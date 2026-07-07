package com.personal_dashboard.backend.dto.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DailyLogRequest {

    private String moodRating;

    private Integer moodScore;

    private String moodNote;
}
