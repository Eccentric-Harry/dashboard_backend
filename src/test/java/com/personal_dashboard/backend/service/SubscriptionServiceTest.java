package com.personal_dashboard.backend.service;

import com.personal_dashboard.backend.dto.SubscriptionDTO;
import com.personal_dashboard.backend.dto.TransactionDTO;
import com.personal_dashboard.backend.dto.request.SubscriptionPaymentRequest;
import com.personal_dashboard.backend.dto.request.SubscriptionRequest;
import com.personal_dashboard.backend.dto.request.TransactionRequest;
import com.personal_dashboard.backend.model.Subscription;
import com.personal_dashboard.backend.repository.SubscriptionRepository;
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
import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SubscriptionServiceTest {

    @Mock
    private SubscriptionRepository repository;

    @Mock
    private FinanceService financeService;

    @InjectMocks
    private SubscriptionService service;

    private Subscription jio;

    @BeforeEach
    void setUp() {
        UserContext.setUserId("u1");
        // Stored the way the old controller wrote it: IST midnight as an Instant, no cycle fields.
        jio = Subscription.builder()
                .id("s1").userId("u1").name("Jio Prepaid").cost(new BigDecimal("349"))
                .billingDate(Instant.parse("2026-06-29T18:30:00Z")).build();
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void legacyRowsReadWithDefaultsAndALocalBillingDate() {
        SubscriptionDTO dto = SubscriptionService.toDto(jio);
        assertEquals("2026-06-30", dto.getBillingDate(), "IST midnight must not slip to the previous UTC day");
        assertEquals("MONTH", dto.getIntervalUnit());
        assertEquals(1, dto.getIntervalCount());
        assertEquals("Subscriptions", dto.getCategory());
        assertEquals(349.0, dto.getMonthlyCost());
    }

    @Test
    void updateEditsEveryField() {
        when(repository.findById("s1")).thenReturn(Optional.of(jio));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        SubscriptionDTO dto = service.update("s1", SubscriptionRequest.builder()
                .name(" Jio Prepaid ").cost(new BigDecimal("399")).billingDate("2026-09-19")
                .category("Bills").intervalUnit("DAY").intervalCount(28).build());

        assertEquals("Jio Prepaid", dto.getName());
        assertEquals(new BigDecimal("399"), dto.getCost());
        assertEquals("2026-09-19", dto.getBillingDate());
        assertEquals("Bills", dto.getCategory());
        assertEquals("DAY", dto.getIntervalUnit());
        assertEquals(28, dto.getIntervalCount());
        // 399 × 30.4375 / 28
        assertEquals(433.73, dto.getMonthlyCost(), 0.01);
    }

    @Test
    void cannotEditSomeoneElsesBill() {
        jio.setUserId("someone-else");
        when(repository.findById("s1")).thenReturn(Optional.of(jio));

        assertThrows(IllegalArgumentException.class, () -> service.update("s1", SubscriptionRequest.builder()
                .name("x").cost(BigDecimal.ONE).build()));
        verify(repository, never()).save(any());
    }

    @Test
    void payLogsALinkedExpenseInTheBillsOwnCategory() {
        jio.setCategory("Bills");
        when(repository.findById("s1")).thenReturn(Optional.of(jio));
        when(financeService.createTransaction(any())).thenReturn(TransactionDTO.builder().id("tx").build());

        service.pay("s1", SubscriptionPaymentRequest.builder().date("2026-09-19").amount(new BigDecimal("399")).build());

        ArgumentCaptor<TransactionRequest> captor = ArgumentCaptor.forClass(TransactionRequest.class);
        verify(financeService).createTransaction(captor.capture());
        TransactionRequest tx = captor.getValue();
        assertEquals("s1", tx.getSubscriptionId());
        assertEquals("Bills", tx.getCategory());
        assertEquals("Expense", tx.getType());
        assertEquals("2026-09-19", tx.getDate());
        assertEquals(new BigDecimal("399"), tx.getAmount());
    }

    @Test
    void payDefaultsToTheBillsCostAndToday() {
        when(repository.findById("s1")).thenReturn(Optional.of(jio));
        when(financeService.createTransaction(any())).thenReturn(TransactionDTO.builder().id("tx").build());

        service.pay("s1", null);

        ArgumentCaptor<TransactionRequest> captor = ArgumentCaptor.forClass(TransactionRequest.class);
        verify(financeService).createTransaction(captor.capture());
        assertEquals(new BigDecimal("349"), captor.getValue().getAmount());
        assertEquals("Subscriptions", captor.getValue().getCategory());
        assertNotNull(captor.getValue().getDate());
    }

    @Test
    void monthlyCostNormalisesEveryCycle() {
        assertEquals(100.0, SubscriptionService.monthlyCost(new BigDecimal("1200"), "YEAR", 1));
        assertEquals(50.0, SubscriptionService.monthlyCost(new BigDecimal("300"), "MONTH", 6));
        assertEquals(434.82, SubscriptionService.monthlyCost(new BigDecimal("100"), "WEEK", 1), 0.01);
    }
}
