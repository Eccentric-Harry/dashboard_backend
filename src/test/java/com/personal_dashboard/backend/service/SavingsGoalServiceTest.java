package com.personal_dashboard.backend.service;

import com.personal_dashboard.backend.dto.SavingsGoalDTO;
import com.personal_dashboard.backend.dto.TransactionDTO;
import com.personal_dashboard.backend.dto.request.GoalMoneyRequest;
import com.personal_dashboard.backend.dto.request.GoalPurchaseRequest;
import com.personal_dashboard.backend.dto.request.SavingsGoalRequest;
import com.personal_dashboard.backend.dto.request.TransactionRequest;
import com.personal_dashboard.backend.model.FinancialTransaction;
import com.personal_dashboard.backend.model.SavingsGoal;
import com.personal_dashboard.backend.repository.SavingsGoalRepository;
import com.personal_dashboard.backend.security.UserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SavingsGoalServiceTest {

    @Mock
    private SavingsGoalRepository repository;

    @Mock
    private FinanceService financeService;

    @InjectMocks
    private SavingsGoalService service;

    private SavingsGoal phone;

    @BeforeEach
    void setUp() {
        UserContext.setUserId("u1");
        phone = SavingsGoal.builder()
                .id("g1").userId("u1").name("iPhone 18 Pro").kind("PURCHASE")
                .targetAmount(new BigDecimal("164900")).status(SavingsGoal.ACTIVE).startDate("2026-10-02").build();
        lenient().when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(financeService.createTransaction(any())).thenReturn(TransactionDTO.builder().id("tx").build());
        lenient().when(financeService.tallyGoals()).thenReturn(Map.of());
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    /** A tally holding {@code saved} for goal g1, built through the same path the ledger uses. */
    private void holding(String saved) {
        FinanceService.GoalTally tally = new FinanceService.GoalTally();
        tally.add(FinancialTransaction.builder().amount(new BigDecimal(saved)).type("Transfer").direction("OUT")
                .goalId("g1").build(), "2026-10-01");
        when(financeService.tallyGoals()).thenReturn(Map.of("g1", tally));
    }

    private List<TransactionRequest> loggedRows(int count) {
        ArgumentCaptor<TransactionRequest> captor = ArgumentCaptor.forClass(TransactionRequest.class);
        verify(financeService, times(count)).createTransaction(captor.capture());
        return captor.getAllValues();
    }

    @Test
    void aPurchaseTargetIsThePriceLessExchangeAndCardOffer() {
        when(repository.findByUserId("u1")).thenReturn(new ArrayList<>());

        SavingsGoalDTO dto = service.create(SavingsGoalRequest.builder()
                .name(" iPhone 18 Pro ").listPrice(new BigDecimal("164900"))
                .exchangeValue(new BigDecimal("30000")).cardOffer(new BigDecimal("5000"))
                .targetDate("2027-04-01").build());

        assertEquals("iPhone 18 Pro", dto.getName());
        assertEquals(129900.0, dto.getTargetAmount());
        assertEquals("PURCHASE", dto.getKind());
        assertEquals("ACTIVE", dto.getStatus());
        assertEquals(0.0, dto.getSaved());
        assertNotNull(dto.getStartDate());
    }

    @Test
    void onlyOpenGoalsMayHaveNoTarget() {
        when(repository.findByUserId("u1")).thenReturn(new ArrayList<>());

        assertThrows(IllegalArgumentException.class,
                () -> service.create(SavingsGoalRequest.builder().name("Trip").kind("TRIP").build()));
        SavingsGoalDTO open = service.create(SavingsGoalRequest.builder().name("Rainy day").kind("OPEN")
                .plannedMonthly(new BigDecimal("2000")).build());
        assertNull(open.getTargetAmount());
    }

    @Test
    void liveGoalsAreCapped() {
        List<SavingsGoal> twelve = new ArrayList<>(Collections.nCopies(12,
                SavingsGoal.builder().userId("u1").status(SavingsGoal.ACTIVE).build()));
        when(repository.findByUserId("u1")).thenReturn(twelve);

        assertThrows(IllegalArgumentException.class, () -> service.create(SavingsGoalRequest.builder()
                .name("One more").targetAmount(BigDecimal.TEN).build()));
        verify(repository, never()).save(any());
    }

    @Test
    void settingAsideIsASavingsTransferOutLinkedToTheGoal() {
        when(repository.findById("g1")).thenReturn(Optional.of(phone));

        service.setAside("g1", GoalMoneyRequest.builder().amount(new BigDecimal("21650")).date("2026-11-01").build());

        TransactionRequest row = loggedRows(1).get(0);
        assertEquals("Transfer", row.getType());
        assertEquals("OUT", row.getDirection());
        assertEquals("Savings", row.getCategory());
        assertEquals("g1", row.getGoalId());
        assertEquals("2026-11-01", row.getDate());
        assertEquals(new BigDecimal("21650"), row.getAmount());
        assertEquals("To iPhone 18 Pro", row.getDescription());
    }

    @Test
    void ledgerLinesSayWhichWayAndWhy() {
        assertEquals("To iPhone 18 Pro · September leftover", SavingsGoalService.describe("OUT", phone, " September leftover "));
        assertEquals("From iPhone 18 Pro · Emergency", SavingsGoalService.describe("IN", phone, "Emergency"));
        assertEquals("From iPhone 18 Pro", SavingsGoalService.describe("IN", phone, null));
    }

    @Test
    void youCanNotTakeOutMoreThanTheGoalHolds() {
        when(repository.findById("g1")).thenReturn(Optional.of(phone));
        holding("10000");

        assertThrows(IllegalArgumentException.class, () -> service.takeOut("g1",
                GoalMoneyRequest.builder().amount(new BigDecimal("10001")).build()));
        verify(financeService, never()).createTransaction(any());

        service.takeOut("g1", GoalMoneyRequest.builder().amount(new BigDecimal("4000")).note("Emergency").build());
        TransactionRequest row = loggedRows(1).get(0);
        assertEquals("IN", row.getDirection());
        assertTrue(row.getDescription().contains("Emergency"));
    }

    @Test
    void buyingReleasesWhatsSavedThenLogsThePurchaseOffBudget() {
        when(repository.findById("g1")).thenReturn(Optional.of(phone));
        holding("150000");

        SavingsGoalDTO dto = service.buy("g1", GoalPurchaseRequest.builder()
                .price(new BigDecimal("164900")).category("Electronics").date("2027-04-02").build());

        List<TransactionRequest> rows = loggedRows(2);
        // All 1,50,000 comes back from the goal; the other 14,900 comes from the balance.
        assertEquals("Transfer", rows.get(0).getType());
        assertEquals("IN", rows.get(0).getDirection());
        assertEquals(new BigDecimal("150000"), rows.get(0).getAmount());
        assertEquals("Expense", rows.get(1).getType());
        assertEquals("Electronics", rows.get(1).getCategory());
        assertEquals("g1", rows.get(1).getGoalId());
        assertEquals(new BigDecimal("164900"), rows.get(1).getAmount());
        assertEquals("BOUGHT", dto.getStatus());
        assertEquals("2027-04-02", dto.getBoughtOn());
        assertEquals(164900.0, dto.getBoughtFor());
    }

    @Test
    void buyingCheaperThanSavedLeavesTheRestInTheGoal() {
        when(repository.findById("g1")).thenReturn(Optional.of(phone));
        holding("164900");

        service.buy("g1", GoalPurchaseRequest.builder().price(new BigDecimal("139900")).build());

        List<TransactionRequest> rows = loggedRows(2);
        assertEquals(new BigDecimal("139900"), rows.get(0).getAmount(), "only the price comes out of the goal");
        assertEquals("Shopping", rows.get(1).getCategory());
        assertEquals("iPhone 18 Pro", rows.get(1).getDescription());
    }

    @Test
    void aBoughtGoalTakesNoMoreMoney() {
        phone.setStatus(SavingsGoal.BOUGHT);
        when(repository.findById("g1")).thenReturn(Optional.of(phone));

        assertThrows(IllegalArgumentException.class, () -> service.setAside("g1",
                GoalMoneyRequest.builder().amount(BigDecimal.TEN).build()));
    }

    @Test
    void archivingWithReleaseReturnsTheMoneyToTheBalance() {
        when(repository.findById("g1")).thenReturn(Optional.of(phone));
        holding("42000");

        SavingsGoalDTO dto = service.archive("g1", true);

        TransactionRequest row = loggedRows(1).get(0);
        assertEquals("IN", row.getDirection());
        assertEquals(new BigDecimal("42000"), row.getAmount());
        assertEquals("ARCHIVED", dto.getStatus());
    }

    @Test
    void archivingWithoutReleaseMovesNoMoney() {
        when(repository.findById("g1")).thenReturn(Optional.of(phone));
        holding("42000");

        service.archive("g1", false);

        verify(financeService, never()).createTransaction(any());
    }

    @Test
    void reopeningABoughtGoalClearsThePurchase() {
        phone.setStatus(SavingsGoal.BOUGHT);
        phone.setBoughtOn("2027-04-02");
        phone.setBoughtFor(new BigDecimal("164900"));
        when(repository.findById("g1")).thenReturn(Optional.of(phone));

        SavingsGoalDTO dto = service.update("g1", SavingsGoalRequest.builder()
                .name("iPhone 18 Pro").targetAmount(new BigDecimal("164900")).status("ACTIVE").build());

        assertEquals("ACTIVE", dto.getStatus());
        assertNull(dto.getBoughtOn());
        assertNull(dto.getBoughtFor());
    }

    @Test
    void cannotTouchSomeoneElsesGoal() {
        phone.setUserId("someone-else");
        when(repository.findById("g1")).thenReturn(Optional.of(phone));

        assertThrows(IllegalArgumentException.class, () -> service.setAside("g1",
                GoalMoneyRequest.builder().amount(BigDecimal.TEN).build()));
        verify(financeService, never()).createTransaction(any());
    }
}
