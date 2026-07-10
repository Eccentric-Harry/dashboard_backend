package com.personal_dashboard.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Client-facing view of a user's running cash balance and monthly budget. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FinanceAccountDTO {

    private Double balance;

    private Double monthlyBudget;
}
