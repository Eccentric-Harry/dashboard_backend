package com.personal_dashboard.backend.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * A user's running cash balance ("Total Balance" on the finance dashboard).
 *
 * <p>Exactly one per user. The balance is a stored running total: income
 * transactions increase it and expense transactions decrease it (applied by
 * {@code FinanceService} on every create/update/delete/import), and the user can
 * also set an absolute value directly ("I have ₹X right now").
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "finance_accounts")
public class FinanceAccount implements UserOwnedDocument {

    @Id
    private String id;

    @Indexed(unique = true)
    private String userId;

    @Builder.Default
    private BigDecimal balance = BigDecimal.ZERO;

    /** User-configurable monthly spending budget. Defaults to 20 000 if not set. */
    @Builder.Default
    private BigDecimal monthlyBudget = BigDecimal.valueOf(20_000);

    /**
     * What the budget covers: "ALL" spending (the default, and the behaviour before this
     * field existed) or "FLEX" — everyday spending only, with fixed costs such as rent and
     * recurring bills planned separately. Null reads as ALL.
     */
    private String budgetScope;

    /** Categories counted as fixed under a FLEX budget. Null means {@code MoneyFlow.DEFAULT_FIXED_CATEGORIES}. */
    private List<String> fixedCategories;

    /**
     * Monthly take-home pay, declared once so savings goals can tell whether a pace fits.
     * Nothing is logged as income today, so without it "can I afford this?" stays unknown
     * — never treated as ₹0. Null = not set.
     */
    private BigDecimal takeHomeMonthly;

    /** Day of the month pay lands, 1–31 (31 = the month's last day). Null = not set. */
    private Integer payday;

    @CreatedDate
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;
}
