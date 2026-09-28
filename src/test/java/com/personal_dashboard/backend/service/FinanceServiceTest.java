package com.personal_dashboard.backend.service;

import com.personal_dashboard.backend.dto.FinanceAccountDTO;
import com.personal_dashboard.backend.dto.ReclassifyResultDTO;
import com.personal_dashboard.backend.dto.request.BudgetUpdateRequest;
import com.personal_dashboard.backend.dto.request.CategoryReclassifyRequest;
import com.personal_dashboard.backend.dto.request.TransactionRequest;
import com.personal_dashboard.backend.model.DailyFinancialLog;
import com.personal_dashboard.backend.model.FinanceAccount;
import com.personal_dashboard.backend.model.FinancialTotals;
import com.personal_dashboard.backend.model.FinancialTransaction;
import com.personal_dashboard.backend.repository.DailyFinancialLogRepository;
import com.personal_dashboard.backend.repository.FinanceAccountRepository;
import com.personal_dashboard.backend.security.UserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FinanceServiceTest {

    @Mock
    private DailyFinancialLogRepository logRepository;

    @Mock
    private FinanceAccountRepository accountRepository;

    @InjectMocks
    private FinanceService financeService;

    private FinanceAccount account;

    @BeforeEach
    void setUp() {
        UserContext.setUserId("u1");
        account = FinanceAccount.builder().userId("u1").balance(new BigDecimal("10000")).build();
        lenient().when(accountRepository.findByUserId("u1")).thenReturn(Optional.of(account));
        lenient().when(accountRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(logRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private static TransactionRequest request(String type, String direction, String category, String amount) {
        return TransactionRequest.builder()
                .description("x").amount(new BigDecimal(amount)).category(category)
                .type(type).direction(direction).date("2026-09-28").build();
    }

    @Test
    void transferOutLowersBalanceButIsNotSpending() {
        DailyFinancialLog day = DailyFinancialLog.builder().userId("u1").dateString("2026-09-28").build();
        when(logRepository.findByUserIdAndDateString("u1", "2026-09-28")).thenReturn(Optional.of(day));

        financeService.createTransaction(request("Transfer", null, "Family", "5000"));

        assertEquals(new BigDecimal("5000"), account.getBalance());
        FinancialTotals totals = day.getDailyTotals();
        assertEquals(0, totals.getTotalExpense().signum(), "a transfer must never count as spending");
        assertEquals(new BigDecimal("5000"), totals.getTotalTransferOut());
        FinancialTransaction saved = day.getTransactions().get("Family").get(0);
        assertEquals("OUT", saved.getDirection());
    }

    @Test
    void transferInRaisesBalanceButIsNotIncome() {
        DailyFinancialLog day = DailyFinancialLog.builder().userId("u1").dateString("2026-09-28").build();
        when(logRepository.findByUserIdAndDateString("u1", "2026-09-28")).thenReturn(Optional.of(day));

        financeService.createTransaction(request("Transfer", "IN", "Loan Recovery", "2000"));

        assertEquals(new BigDecimal("12000"), account.getBalance());
        assertEquals(0, day.getDailyTotals().getTotalIncome().signum());
        assertEquals(new BigDecimal("2000"), day.getDailyTotals().getTotalTransferIn());
    }

    @Test
    void editKeepsTheBillLinkWhenTheFormOmitsIt() {
        FinancialTransaction paid = FinancialTransaction.builder()
                .id("tx1").description("Netflix").amount(new BigDecimal("199")).type("Expense")
                .subscriptionId("sub-9").timestamp(Instant.parse("2026-09-28T06:00:00Z")).build();
        DailyFinancialLog day = dayWith("Subscriptions", paid);
        when(logRepository.findByUserId("u1")).thenReturn(List.of(day));
        when(logRepository.findByUserIdAndDateString(anyString(), anyString())).thenReturn(Optional.of(day));

        financeService.updateTransaction("tx1", request("Expense", null, "Subscriptions", "249"));

        FinancialTransaction edited = day.getTransactions().get("Subscriptions").get(0);
        assertEquals("sub-9", edited.getSubscriptionId());
        assertEquals(new BigDecimal("249"), edited.getAmount());
        // 10000 + 199 (reversed) − 249 (re-applied)
        assertEquals(new BigDecimal("9950"), account.getBalance());
    }

    @Test
    void reclassifyTurnsLegacyHomeSpendingIntoFamilyTransfersWithoutMovingBalance() {
        FinancialTransaction gold = FinancialTransaction.builder()
                .id("g").description("Gold").amount(new BigDecimal("30000")).type("Expense").build();
        FinancialTransaction food = FinancialTransaction.builder()
                .id("f").description("Lunch").amount(new BigDecimal("200")).type("Expense").build();
        DailyFinancialLog day = dayWith("To Home", gold);
        day.getTransactions().put("Food", new ArrayList<>(List.of(food)));
        day.setDailyTotals(FinancialTotals.builder().totalExpense(new BigDecimal("30200")).build());
        when(logRepository.findByUserId("u1")).thenReturn(List.of(day));

        ReclassifyResultDTO result = financeService.reclassifyCategory(CategoryReclassifyRequest.builder()
                .category("To Home").targetCategory("Family").type("Transfer").direction("OUT").build());

        assertEquals(1, result.getUpdated());
        assertEquals(0.0, result.getBalanceDelta());
        assertEquals(new BigDecimal("10000"), account.getBalance());
        assertFalse(day.getTransactions().containsKey("To Home"));
        assertEquals("Transfer", day.getTransactions().get("Family").get(0).getType());
        assertEquals(0, new BigDecimal("200").compareTo(day.getDailyTotals().getTotalExpense()));
        assertEquals(0, new BigDecimal("30000").compareTo(day.getDailyTotals().getTotalTransferOut()));
    }

    @Test
    void reclassifyWithoutTypeIsAPureMergeThatKeepsEachRowsType() {
        FinancialTransaction jio = FinancialTransaction.builder()
                .id("j").description("Jio").amount(new BigDecimal("399")).type("Expense").build();
        DailyFinancialLog day = dayWith("Bills & Utilities", jio);
        day.getTransactions().put("Bills", new ArrayList<>());
        when(logRepository.findByUserId("u1")).thenReturn(List.of(day));

        financeService.reclassifyCategory(CategoryReclassifyRequest.builder()
                .category("Bills & Utilities").targetCategory("Bills").build());

        assertEquals(List.of(jio), day.getTransactions().get("Bills"));
        assertEquals("Expense", jio.getType());
        assertNull(jio.getDirection());
    }

    @Test
    void budgetScopeIsOptionalAndDefaultsToAll() {
        FinanceAccountDTO initial = financeService.getAccount();
        assertEquals("ALL", initial.getBudgetScope());
        assertTrue(initial.getFixedCategories().contains("Rent"));

        FinanceAccountDTO flex = financeService.updateBudget(BudgetUpdateRequest.builder()
                .monthlyBudget(new BigDecimal("12000")).budgetScope("FLEX")
                .fixedCategories(List.of(" Rent ", "Bills", "Rent")).build());
        assertEquals("FLEX", flex.getBudgetScope());
        assertEquals(List.of("Rent", "Bills"), flex.getFixedCategories());

        // A budget-only update must not reset the scope.
        FinanceAccountDTO amountOnly = financeService.setMonthlyBudget(new BigDecimal("15000"));
        assertEquals("FLEX", amountOnly.getBudgetScope());
        assertEquals(15000.0, amountOnly.getMonthlyBudget());
    }

    private static DailyFinancialLog dayWith(String category, FinancialTransaction tx) {
        Map<String, List<FinancialTransaction>> byCategory = new LinkedHashMap<>();
        byCategory.put(category, new ArrayList<>(List.of(tx)));
        return DailyFinancialLog.builder()
                .userId("u1").dateString("2026-09-28")
                .dailyTotals(FinancialTotals.builder().totalExpense(tx.getAmount()).build())
                .transactions(byCategory)
                .build();
    }
}
