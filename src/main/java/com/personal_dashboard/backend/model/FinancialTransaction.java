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
 * <p>Self-sufficient: it carries its own {@code type} so a client never has to infer
 * meaning from the category name — see {@link com.personal_dashboard.backend.util.MoneyFlow}
 * for what Expense / Income / Transfer each count toward. The parent log groups these by
 * category (the map key), so {@code category} is intentionally not stored here.
 *
 * <p>Spring Data maps the {@code id} property of an embedded type to the {@code _id} key.
 * Rows written by an early import used a literal {@code id} key and read back with a null id
 * until {@code FinanceTransactionIdMigration} renamed them.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FinancialTransaction {
    private String id;
    private String description;
    private BigDecimal amount;
    private String type; // "Expense" | "Income" | "Transfer"
    /** Transfers only: "OUT" (sent home, lent, saved) or "IN" (loan repaid to you). */
    private String direction;
    /** Set when this row is a payment against a recurring bill ({@link Subscription}). */
    private String subscriptionId;
    /**
     * Set when this row moves money into or out of a savings goal ({@link SavingsGoal}):
     * a Transfer OUT sets money aside, a Transfer IN takes it back out, and an Expense is a
     * purchase paid for from the goal. A goal's progress is derived from these rows.
     */
    private String goalId;
    private Instant timestamp;
}
