package com.personal_dashboard.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** Client-facing view of a user's running cash balance and monthly budget settings. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FinanceAccountDTO {

    private Double balance;

    private Double monthlyBudget;

    /** "ALL" or "FLEX" — see {@code FinanceAccount.budgetScope}. Never null. */
    private String budgetScope;

    /** Categories treated as fixed under a FLEX budget (defaults applied). Never null. */
    private List<String> fixedCategories;
}
