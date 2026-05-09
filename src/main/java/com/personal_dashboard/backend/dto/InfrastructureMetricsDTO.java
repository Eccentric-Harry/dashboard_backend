package com.personal_dashboard.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InfrastructureMetricsDTO {

    private String status; // "Healthy", "Degraded", "Down"

    private Integer activeConnections;

    private Integer slowQueryCount;

    private String replicaSetPrimary;

    private Integer uptimeHours;
}
