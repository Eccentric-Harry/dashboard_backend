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
public class FinanceMetrics {

    private String month;

    private Double totalBudget;

    private Double totalSpent;

    private Double savingsRatePercent;

    private List<BudgetItem> budgetItems;

    private List<TransactionDTO> transactions;
}
