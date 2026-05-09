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
public class CodingMetrics {

    private List<LearningHeatmapEntry> learningHeatmap;

    private CodingStats stats;

    private PlatformMetricPlaceholder leetCode;

    private PlatformMetricPlaceholder github;
}
