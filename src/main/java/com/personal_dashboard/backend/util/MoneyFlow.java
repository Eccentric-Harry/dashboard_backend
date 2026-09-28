package com.personal_dashboard.backend.util;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The single definition of what a finance transaction <em>means</em> — every total, budget
 * and balance in the app classifies money through here, so /finance, /home and the
 * dashboard aggregates can never disagree about what counts as spending.
 *
 * <p>Three kinds of money movement:
 * <ul>
 *   <li><b>Expense</b> — your own spending. Counts toward spending, the budget and the
 *       category breakdown.</li>
 *   <li><b>Income</b> — money you earned or received to spend.</li>
 *   <li><b>Transfer</b> — money that moves but is not consumption: sent home to family,
 *       lent to a friend, a loan paid back to you, moved into savings. It changes the
 *       running balance (OUT lowers it, IN raises it) but is excluded from spending,
 *       income, the budget and every "where did my money go" breakdown.</li>
 * </ul>
 *
 * <p>A {@code null} type is <b>spending</b>, not income: legacy rows written before the type
 * was persisted were all expenses (see DashboardService history — 140 of 301 production rows
 * once had no type). Keep this rule identical to {@code lib/finance-ledger.ts#txKind}.
 */
public final class MoneyFlow {

    public static final String EXPENSE = "Expense";
    public static final String INCOME = "Income";
    public static final String TRANSFER = "Transfer";

    public static final String OUT = "OUT";
    public static final String IN = "IN";

    public static final String SCOPE_ALL = "ALL";
    public static final String SCOPE_FLEX = "FLEX";

    /**
     * Categories treated as fixed costs when the budget covers flexible spending only
     * (Monarch-style flex budgeting). The user can override the list per account; a
     * transaction linked to a recurring bill is always fixed regardless of category.
     */
    public static final List<String> DEFAULT_FIXED_CATEGORIES = List.of(
            "Rent", "Bills", "Bills & Utilities", "Utilities", "Subscriptions", "Insurance", "EMI");

    private MoneyFlow() {
    }

    public static boolean isIncome(String type) {
        return INCOME.equalsIgnoreCase(type);
    }

    public static boolean isTransfer(String type) {
        return TRANSFER.equalsIgnoreCase(type);
    }

    /** Spending = anything that is neither income nor a transfer (so legacy null types count). */
    public static boolean isSpending(String type) {
        return !isIncome(type) && !isTransfer(type);
    }

    /** A transfer's direction, defaulting to OUT; {@code null} for non-transfers. */
    public static String normalizeDirection(String type, String direction) {
        if (!isTransfer(type)) {
            return null;
        }
        return IN.equalsIgnoreCase(direction) ? IN : OUT;
    }

    /** How a transaction moves the running Total Balance: + for money in, − for money out. */
    public static BigDecimal balanceEffect(String type, String direction, BigDecimal amount) {
        if (amount == null) {
            return BigDecimal.ZERO;
        }
        boolean moneyIn = isIncome(type) || (isTransfer(type) && IN.equalsIgnoreCase(direction));
        return moneyIn ? amount : amount.negate();
    }

    public static String normalizeScope(String scope) {
        return SCOPE_FLEX.equalsIgnoreCase(scope) ? SCOPE_FLEX : SCOPE_ALL;
    }

    /** Lower-cased lookup set for {@link #isFixed}; falls back to the defaults when unset. */
    public static Set<String> fixedCategorySet(Collection<String> configured) {
        Collection<String> source = configured == null ? DEFAULT_FIXED_CATEGORIES : configured;
        return source.stream()
                .filter(c -> c != null && !c.isBlank())
                .map(c -> c.trim().toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());
    }

    /** A fixed cost: in a fixed category, or paid against a recurring bill. */
    public static boolean isFixed(String category, String subscriptionId, Set<String> fixedLower) {
        if (subscriptionId != null && !subscriptionId.isBlank()) {
            return true;
        }
        return category != null && fixedLower.contains(category.trim().toLowerCase(Locale.ROOT));
    }

    /** Whether a spending row counts against the monthly budget under {@code scope}. */
    public static boolean countsTowardBudget(String scope, String category, String subscriptionId,
            Set<String> fixedLower) {
        return !SCOPE_FLEX.equals(normalizeScope(scope)) || !isFixed(category, subscriptionId, fixedLower);
    }
}
