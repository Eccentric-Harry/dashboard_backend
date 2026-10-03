package com.personal_dashboard.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TransactionDTO {

    private String id;

    private String description;

    private Double amount;

    private String category;

    private String type; // "Expense" | "Income" | "Transfer"

    /** Transfers only: "OUT" | "IN". */
    private String direction;

    /** Recurring bill this row pays, if any. */
    private String subscriptionId;

    /** Savings goal this row sets aside for, takes out of, or was bought from, if any. */
    private String goalId;

    private String date;
}
