package com.personal_dashboard.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BudgetItem {

    private String category;

    private Double budget;

    private Double spent;

    private Double remaining;

    private Double utilizationPercent;

    private String status; // "safe" | "warning" | "danger"
}
