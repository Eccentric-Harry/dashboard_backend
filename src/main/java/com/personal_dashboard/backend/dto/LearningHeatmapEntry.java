package com.personal_dashboard.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LearningHeatmapEntry {

    private String date;

    private Integer intensity; // 0 | 1 | 2 | 3 | 4

    private String topic;
}
