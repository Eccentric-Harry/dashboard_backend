package com.personal_dashboard.backend.service;

import com.personal_dashboard.backend.dto.SavingsGoalDTO;
import com.personal_dashboard.backend.dto.TransactionDTO;
import com.personal_dashboard.backend.dto.WishlistItemDTO;
import com.personal_dashboard.backend.dto.request.GoalPurchaseRequest;
import com.personal_dashboard.backend.dto.request.SavingsGoalRequest;
import com.personal_dashboard.backend.dto.request.TransactionRequest;
import com.personal_dashboard.backend.dto.request.WishlistBuyRequest;
import com.personal_dashboard.backend.dto.request.WishlistItemRequest;
import com.personal_dashboard.backend.model.SavingsGoal;
import com.personal_dashboard.backend.model.WishlistItem;
import com.personal_dashboard.backend.repository.SavingsGoalRepository;
import com.personal_dashboard.backend.repository.WishlistItemRepository;
import com.personal_dashboard.backend.security.UserContext;
import com.personal_dashboard.backend.service.showcase.GoalShowcaseService;
import com.personal_dashboard.backend.service.showcase.ProductLinkPreviewer;
import com.personal_dashboard.backend.service.showcase.ProductLinkPreviewer.LinkPreview;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WishlistServiceTest {

    @Mock private WishlistItemRepository repository;
    @Mock private SavingsGoalRepository savingsGoalRepository;
    @Mock private SavingsGoalService savingsGoalService;
    @Mock private GoalShowcaseService goalShowcaseService;
    @Mock private FinanceService financeService;
    @Mock private ProductLinkPreviewer previewer;

    @InjectMocks
    private WishlistService service;

    private final List<WishlistItem> stored = new ArrayList<>();

    @BeforeEach
    void setUp() {
        UserContext.setUserId("u1");
        stored.clear();
        lenient().when(repository.findByUserId("u1")).thenAnswer(inv -> new ArrayList<>(stored));
        lenient().when(repository.findById(any())).thenAnswer(inv ->
                stored.stream().filter(w -> w.getId().equals(inv.getArgument(0))).findFirst());
        lenient().when(repository.save(any())).thenAnswer(inv -> {
            WishlistItem w = inv.getArgument(0);
            if (w.getId() == null) {
                w.setId("w" + (stored.size() + 1));
                w.setUserId("u1");
                stored.add(w);
            }
            return w;
        });
        lenient().when(financeService.createTransaction(any())).thenReturn(TransactionDTO.builder().id("tx1").build());
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private WishlistItem seed(String id, String name, BigDecimal price) {
        WishlistItem w = WishlistItem.builder().id(id).userId("u1").name(name).store("Amazon")
                .url("https://www.amazon.in/dp/X").imageUrl("https://m.media-amazon.com/images/I/x.jpg")
                .price(price).firstPrice(price).priority(WishlistItem.WANT).status(WishlistItem.WANTED)
                .createdAt(Instant.parse("2026-10-01T10:00:00Z")).build();
        stored.add(w);
        return w;
    }

    @Test
    void createKeepsThePriceAsTheBaselineAndDefaultsToAWant() {
        WishlistItemDTO out = service.create(WishlistItemRequest.builder()
                .name("  Nike   Pegasus 41 ").url("amazon.in/dp/X").price(new BigDecimal("9995")).build());

        assertEquals("Nike Pegasus 41", out.getName());
        assertEquals("https://amazon.in/dp/X", out.getUrl());
        assertEquals(9995.0, out.getFirstPrice());
        assertEquals("WANT", out.getPriority());
        assertEquals("WANTED", out.getStatus());
    }

    @Test
    void aNonWebImageLinkIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> service.create(WishlistItemRequest.builder()
                .name("x").imageUrl("javascript:alert(1)").build()));
    }

    @Test
    void buyingLogsAnExpenseNamedAfterTheWishAndItsStore() {
        seed("w1", "Nike Pegasus 41", new BigDecimal("9995"));

        WishlistItemDTO out = service.buy("w1", WishlistBuyRequest.builder()
                .price(new BigDecimal("8999")).date("2026-10-06").build());

        ArgumentCaptor<TransactionRequest> tx = ArgumentCaptor.forClass(TransactionRequest.class);
        verify(financeService).createTransaction(tx.capture());
        assertEquals("Nike Pegasus 41 · Amazon", tx.getValue().getDescription());
        assertEquals("Shopping", tx.getValue().getCategory());
        assertEquals("Expense", tx.getValue().getType());
        assertEquals(0, new BigDecimal("8999").compareTo(tx.getValue().getAmount()));
        assertEquals("BOUGHT", out.getStatus());
        assertEquals("tx1", out.getTransactionId());
        assertEquals(8999.0, out.getBoughtFor());
        verifyNoInteractions(savingsGoalService);
    }

    @Test
    void buyingAWishWithASavingsGoalGoesThroughTheGoal() {
        WishlistItem w = seed("w1", "iPhone 18 Pro", new BigDecimal("164900"));
        w.setSavingsGoalId("g1");
        when(savingsGoalRepository.findById("g1")).thenReturn(Optional.of(
                SavingsGoal.builder().id("g1").userId("u1").status(SavingsGoal.ACTIVE).build()));

        service.buy("w1", WishlistBuyRequest.builder().price(new BigDecimal("159900")).category("Gadgets").build());

        ArgumentCaptor<GoalPurchaseRequest> purchase = ArgumentCaptor.forClass(GoalPurchaseRequest.class);
        verify(savingsGoalService).buy(eq("g1"), purchase.capture());
        assertEquals("Gadgets", purchase.getValue().getCategory());
        verify(financeService, never()).createTransaction(any());
    }

    @Test
    void aClosedWishCannotBeBoughtTwice() {
        seed("w1", "x", BigDecimal.TEN).setStatus(WishlistItem.BOUGHT);
        assertThrows(IllegalArgumentException.class, () ->
                service.buy("w1", WishlistBuyRequest.builder().price(BigDecimal.ONE).build()));
        verifyNoInteractions(financeService);
    }

    @Test
    void savingForCreatesAPurchaseGoalWithThePhoto() {
        seed("w1", "A very long product name that will not fit in a goal", new BigDecimal("24990"));
        when(savingsGoalService.create(any())).thenReturn(SavingsGoalDTO.builder().id("g9").build());

        WishlistItemDTO out = service.saveFor("w1");

        ArgumentCaptor<SavingsGoalRequest> goal = ArgumentCaptor.forClass(SavingsGoalRequest.class);
        verify(savingsGoalService).create(goal.capture());
        assertTrue(goal.getValue().getName().length() <= 40);
        assertEquals("PURCHASE", goal.getValue().getKind());
        assertEquals(0, new BigDecimal("24990").compareTo(goal.getValue().getListPrice()));
        verify(goalShowcaseService).find("g9", "https://m.media-amazon.com/images/I/x.jpg");
        assertEquals("g9", out.getSavingsGoalId());
    }

    @Test
    void aGoalStillGetsCreatedWhenThePhotoCannotBeRead() {
        seed("w1", "Shoes", new BigDecimal("5000"));
        when(savingsGoalService.create(any())).thenReturn(SavingsGoalDTO.builder().id("g9").build());
        when(goalShowcaseService.find(any(), any())).thenThrow(new IllegalArgumentException("too small"));

        assertEquals("g9", service.saveFor("w1").getSavingsGoalId());
    }

    @Test
    void savingForNeedsAPrice() {
        seed("w1", "Shoes", null);
        assertThrows(IllegalArgumentException.class, () -> service.saveFor("w1"));
        verifyNoInteractions(savingsGoalService);
    }

    @Test
    void aGoalBoughtOnFinanceClosesTheWishOnTheNextRead() {
        seed("w1", "iPhone", new BigDecimal("100")).setSavingsGoalId("g1");
        seed("w2", "Bag", new BigDecimal("50")).setSavingsGoalId("g2");
        when(savingsGoalRepository.findByUserId("u1")).thenReturn(List.of(
                SavingsGoal.builder().id("g1").userId("u1").status(SavingsGoal.BOUGHT)
                        .boughtOn("2026-10-05").boughtFor(new BigDecimal("95")).build(),
                SavingsGoal.builder().id("g2").userId("u1").status(SavingsGoal.ARCHIVED).build()));

        List<WishlistItemDTO> out = service.list();

        WishlistItemDTO iphone = out.stream().filter(w -> w.getId().equals("w1")).findFirst().orElseThrow();
        WishlistItemDTO bag = out.stream().filter(w -> w.getId().equals("w2")).findFirst().orElseThrow();
        assertEquals("BOUGHT", iphone.getStatus());
        assertEquals(95.0, iphone.getBoughtFor());
        assertNull(bag.getSavingsGoalId());
        assertEquals("WANTED", bag.getStatus());
        verify(repository).saveAll(any());
    }

    @Test
    void listPutsNeedsFirstThenNewestThenClosed() {
        WishlistItem old = seed("a", "Old want", BigDecimal.ONE);
        WishlistItem fresh = seed("b", "New want", BigDecimal.ONE);
        fresh.setCreatedAt(Instant.parse("2026-10-05T10:00:00Z"));
        WishlistItem need = seed("c", "Need", BigDecimal.ONE);
        need.setPriority(WishlistItem.NEED);
        WishlistItem gone = seed("d", "Gone", BigDecimal.ONE);
        gone.setStatus(WishlistItem.LET_GO);
        gone.setClosedAt(Instant.now());

        assertEquals(List.of("c", "b", "a", "d"), service.list().stream().map(WishlistItemDTO::getId).toList());
        assertNotNull(old);
    }

    @Test
    void refreshKeepsTheFirstPriceAndTakesTheNewOne() {
        seed("w1", "Shoes", new BigDecimal("5000"));
        when(previewer.preview(any())).thenReturn(new LinkPreview("u", "t", null, "Amazon", new BigDecimal("4500"), true));

        WishlistItemDTO out = service.refresh("w1");

        assertEquals(4500.0, out.getPrice());
        assertEquals(5000.0, out.getFirstPrice());
        assertNotNull(out.getPriceCheckedAt());
    }

    @Test
    void aBlockedRefreshChangesNothingAndSaysWhy() {
        seed("w1", "Shoes", new BigDecimal("5000"));
        when(previewer.preview(any())).thenReturn(new LinkPreview("u", "t", null, "Flipkart", null, false));

        assertThrows(IllegalArgumentException.class, () -> service.refresh("w1"));
        verify(repository, never()).save(any());
    }

    @Test
    void letGoAndReopen() {
        seed("w1", "Shoes", new BigDecimal("5000"));
        assertEquals("LET_GO", service.letGo("w1").getStatus());
        WishlistItemDTO back = service.reopen("w1");
        assertEquals("WANTED", back.getStatus());
        assertNull(back.getClosedAt());
    }

    @Test
    void anotherUsersWishIsNotFound() {
        stored.add(WishlistItem.builder().id("x").userId("someone-else").name("x").status(WishlistItem.WANTED).build());
        assertThrows(IllegalArgumentException.class, () -> service.letGo("x"));
        assertThrows(IllegalArgumentException.class, () -> service.delete("x"));
        assertThrows(IllegalArgumentException.class, () ->
                service.buy("x", WishlistBuyRequest.builder().price(BigDecimal.ONE).build()));
        verifyNoInteractions(financeService);
    }
}
