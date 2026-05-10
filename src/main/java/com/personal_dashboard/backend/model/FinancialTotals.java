package com.personal_dashboard.backend.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FinancialTotals {

    @Builder.Default
    private BigDecimal totalExpense = BigDecimal.ZERO;
    
    @Builder.Default
    private BigDecimal totalIncome = BigDecimal.ZERO;
}
