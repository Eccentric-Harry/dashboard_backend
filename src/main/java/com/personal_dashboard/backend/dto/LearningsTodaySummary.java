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
public class LearningsTodaySummary {

    private Integer learningsCount;
    private Integer tasksTotal;
    private Integer tasksCompleted;
    private List<LearningsCategoryCount> categories;
}
