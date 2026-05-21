package com.personal_dashboard.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LearningsTimelineDay {

    private String date;
    private Integer learningsCount;
    private Integer tasksCompleted;
    private Integer intensity;
}
