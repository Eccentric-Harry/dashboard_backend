package com.personal_dashboard.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CircularProgressMetric {

    private String label;

    private Integer value;

    private Integer target;

    private String unit;

    private Double progressPercent;
}
