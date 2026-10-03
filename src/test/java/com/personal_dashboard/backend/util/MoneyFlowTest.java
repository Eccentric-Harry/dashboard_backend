package com.personal_dashboard.backend.util;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class MoneyFlowTest {

    @Test
    void legacyNullTypeIsSpendingNotIncome() {
        assertTrue(MoneyFlow.isSpending(null));
        assertTrue(MoneyFlow.isSpending("Expense"));
        assertFalse(MoneyFlow.isSpending("Income"));
        assertFalse(MoneyFlow.isSpending("Transfer"));
    }

    @Test
    void transfersMoveTheBalanceByDirection() {
        BigDecimal amount = new BigDecimal("5000");
        assertEquals(new BigDecimal("-5000"), MoneyFlow.balanceEffect("Transfer", "OUT", amount));
        assertEquals(new BigDecimal("-5000"), MoneyFlow.balanceEffect("Transfer", null, amount));
        assertEquals(amount, MoneyFlow.balanceEffect("Transfer", "IN", amount));
        assertEquals(amount, MoneyFlow.balanceEffect("Income", null, amount));
        assertEquals(new BigDecimal("-5000"), MoneyFlow.balanceEffect("Expense", "IN", amount));
    }

    @Test
    void directionOnlyExistsOnTransfers() {
        assertNull(MoneyFlow.normalizeDirection("Expense", "IN"));
        assertEquals("OUT", MoneyFlow.normalizeDirection("Transfer", null));
        assertEquals("IN", MoneyFlow.normalizeDirection("Transfer", "in"));
    }

    @Test
    void flexBudgetExcludesFixedCategoriesAndBillPayments() {
        Set<String> fixed = MoneyFlow.fixedCategorySet(null);
        assertFalse(MoneyFlow.countsTowardBudget("FLEX", "Rent", null, null, fixed));
        assertFalse(MoneyFlow.countsTowardBudget("FLEX", " bills ", null, null, fixed));
        assertFalse(MoneyFlow.countsTowardBudget("FLEX", "Entertainment", "sub-1", null, fixed));
        assertTrue(MoneyFlow.countsTowardBudget("FLEX", "Food", null, null, fixed));
        // ALL (and anything unknown) counts every spending row.
        assertTrue(MoneyFlow.countsTowardBudget("ALL", "Rent", null, null, fixed));
        assertTrue(MoneyFlow.countsTowardBudget(null, "Rent", "sub-1", null, fixed));
    }

    @Test
    void customFixedListReplacesDefaults() {
        Set<String> fixed = MoneyFlow.fixedCategorySet(List.of("Rent"));
        assertTrue(MoneyFlow.countsTowardBudget("FLEX", "Bills", null, null, fixed));
        assertFalse(MoneyFlow.countsTowardBudget("FLEX", "rent", null, null, fixed));
    }

    @Test
    void purchasesFromSavingsNeverCountAgainstTheBudget() {
        Set<String> fixed = MoneyFlow.fixedCategorySet(null);
        assertFalse(MoneyFlow.countsTowardBudget("ALL", "Shopping", null, "goal-1", fixed));
        assertFalse(MoneyFlow.countsTowardBudget("FLEX", "Shopping", null, "goal-1", fixed));
        assertTrue(MoneyFlow.countsTowardBudget("ALL", "Shopping", null, " ", fixed));
        assertTrue(MoneyFlow.isFromSavings("goal-1"));
        assertFalse(MoneyFlow.isFromSavings(null));
    }
}
