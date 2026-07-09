package com.personal_dashboard.backend.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A single money movement, embedded inside a {@link DailyFinancialLog}.
 *
 * <p>Self-sufficient: it carries its own {@code type} (Income/Expense) so a client
 * never has to infer the direction from the category name. The parent log groups
 * these by category (the map key), so {@code category} is intentionally not stored here.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FinancialTransaction {
    private String id;
    private String description;
    private BigDecimal amount;
    private String type; // "Expense" or "Income"
    private Instant timestamp;
}
