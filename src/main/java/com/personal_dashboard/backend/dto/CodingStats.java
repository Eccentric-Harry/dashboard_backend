package com.personal_dashboard.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CodingStats {

    private Double focusedHours;

    private Integer deepWorkSessions;

    private Integer weeklyLearningCount;

    private Integer streakDays;
}
