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

    /** Transfers out (sent home, lent, saved) — never part of totalExpense. */
    @Builder.Default
    private BigDecimal totalTransferOut = BigDecimal.ZERO;

    /** Transfers in (a loan paid back, money back from savings) — never part of totalIncome. */
    @Builder.Default
    private BigDecimal totalTransferIn = BigDecimal.ZERO;
}
