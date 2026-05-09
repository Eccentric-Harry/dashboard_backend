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

    private String type; // "Expense" | "Income"

    private String date;
}
