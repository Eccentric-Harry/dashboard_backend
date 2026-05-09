package com.personal_dashboard.backend.controller;

import com.personal_dashboard.backend.dto.ApiMeta;
import com.personal_dashboard.backend.dto.ApiResponse;
import com.personal_dashboard.backend.dto.InfrastructureMetricsDTO;
import com.personal_dashboard.backend.service.InfrastructureService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/infrastructure")
@RequiredArgsConstructor
public class InfrastructureController {

    private final InfrastructureService infrastructureService;

    @GetMapping("/metrics")
    public ResponseEntity<ApiResponse<InfrastructureMetricsDTO>> getInfrastructureMetrics() {

        InfrastructureMetricsDTO metrics = infrastructureService.getServerMetrics();

        ApiMeta meta = ApiMeta.builder()
                .requestId(UUID.randomUUID().toString())
                .timestamp(Instant.now().toString())
                .source("api")
                .build();

        ApiResponse<InfrastructureMetricsDTO> response = ApiResponse.<InfrastructureMetricsDTO>builder()
                .data(metrics)
                .meta(meta)
                .build();

        return ResponseEntity.ok(response);
    }
}
