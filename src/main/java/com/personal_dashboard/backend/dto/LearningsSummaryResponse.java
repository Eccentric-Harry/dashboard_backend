package com.personal_dashboard.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LearningsSummaryResponse {

    private String date;
    private LearningsTodaySummary today;
    private List<LearningsTimelineDay> sevenDayTimeline;
    private LearningsStatsSummary stats;
}
