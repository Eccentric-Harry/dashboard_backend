package com.personal_dashboard.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LearningsStatsSummary {

    private Integer weeklyLearningCount;
    private Integer streakDays;
    private Long totalTasksCompleted;
    private Long totalTasksCount;
    private Long totalLearningsCount;
    private Long totalPursuitsCount;
}
