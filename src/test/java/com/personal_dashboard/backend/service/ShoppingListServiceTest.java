package com.personal_dashboard.backend.service;

import com.personal_dashboard.backend.dto.ShoppingCheckoutDTO;
import com.personal_dashboard.backend.dto.ShoppingItemDTO;
import com.personal_dashboard.backend.dto.ShoppingSuggestionDTO;
import com.personal_dashboard.backend.dto.TransactionDTO;
import com.personal_dashboard.backend.dto.request.ShoppingCheckoutRequest;
import com.personal_dashboard.backend.dto.request.ShoppingItemRequest;
import com.personal_dashboard.backend.dto.request.TransactionRequest;
import com.personal_dashboard.backend.model.ShoppingItem;
import com.personal_dashboard.backend.model.ShoppingMemory;
import com.personal_dashboard.backend.repository.ShoppingItemRepository;
import com.personal_dashboard.backend.repository.ShoppingMemoryRepository;
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
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ShoppingListServiceTest {

    @Mock
    private ShoppingItemRepository repository;

    @Mock
    private ShoppingMemoryRepository memoryRepository;

    @Mock
    private FinanceService financeService;

    @InjectMocks
    private ShoppingListService service;

    private final List<ShoppingItem> stored = new ArrayList<>();
    private ShoppingMemory memory;

    @BeforeEach
    void setUp() {
        UserContext.setUserId("u1");
        stored.clear();
        AtomicInteger ids = new AtomicInteger();
        lenient().when(repository.findByUserId("u1")).thenAnswer(inv -> new ArrayList<>(stored));
        lenient().when(repository.findById(any())).thenAnswer(inv ->
                stored.stream().filter(i -> i.getId().equals(inv.getArgument(0))).findFirst());
        lenient().when(repository.findByUserIdAndChecked(eq("u1"), anyBoolean())).thenAnswer(inv ->
                stored.stream().filter(i -> i.isChecked() == (boolean) inv.getArgument(1)).toList());
        lenient().when(repository.saveAll(any())).thenAnswer(inv -> {
            Iterable<ShoppingItem> items = inv.getArgument(0);
            items.forEach(i -> {
                if (i.getId() == null) {
                    i.setId("s" + ids.incrementAndGet());
                    i.setUserId("u1");
                    i.setCreatedAt(Instant.parse("2026-10-06T10:00:00Z").plusSeconds(ids.get()));
                    stored.add(i);
                }
            });
            return StreamSupport.stream(items.spliterator(), false).toList();
        });
        lenient().when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        memory = null;
        lenient().when(memoryRepository.findFirstByUserId("u1")).thenAnswer(inv -> Optional.ofNullable(memory));
        lenient().when(memoryRepository.save(any())).thenAnswer(inv -> {
            memory = inv.getArgument(0);
            return memory;
        });
        lenient().when(financeService.createTransaction(any())).thenReturn(TransactionDTO.builder().id("tx1").build());
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private static ShoppingItemRequest req(String name) {
        return ShoppingItemRequest.builder().name(name).build();
    }

    private ShoppingItem seed(String id, String name, String category, boolean checked) {
        ShoppingItem item = ShoppingItem.builder().id(id).userId("u1").name(name).category(category)
                .checked(checked).checkedAt(checked ? Instant.now() : null)
                .createdAt(Instant.parse("2026-10-06T09:00:00Z")).build();
        stored.add(item);
        return item;
    }

    // ─── Adding ────────────────────────────────────────────────────────────

    @Test
    void addFilesAnItemByItsNameAndCleansTheText() {
        List<ShoppingItemDTO> out = service.addAll(List.of(req("  tomato   ketchup ")));

        assertEquals("Tomato ketchup", out.get(0).getName());
        assertEquals("PANTRY", out.get(0).getCategory());
        assertFalse(out.get(0).isChecked());
        assertNotNull(out.get(0).getId());
    }

    @Test
    void anExplicitCategoryBeatsTheGuess() {
        ShoppingItemRequest r = ShoppingItemRequest.builder().name("Milk").category("household").build();
        assertEquals("HOUSEHOLD", service.addAll(List.of(r)).get(0).getCategory());
    }

    @Test
    void anUnknownCategoryIsRejected() {
        ShoppingItemRequest r = ShoppingItemRequest.builder().name("Milk").category("PLUMBING").build();
        assertThrows(IllegalArgumentException.class, () -> service.addAll(List.of(r)));
        verify(repository, never()).saveAll(any());
    }

    @Test
    void batchAddIsOneReadAndOneWrite() {
        List<ShoppingItemDTO> out = service.addAll(List.of(req("milk"), req("bread"), req("soap")));

        assertEquals(List.of("DAIRY", "BAKERY", "HOUSEHOLD"), out.stream().map(ShoppingItemDTO::getCategory).toList());
        verify(repository, times(1)).findByUserId("u1");
        verify(repository, times(1)).saveAll(any());
    }

    @Test
    void addingAnItemAlreadyToGetDoesNotDuplicateIt() {
        seed("a", "Milk", "DAIRY", false);

        List<ShoppingItemDTO> out = service.addAll(List.of(req("milk")));

        assertEquals("a", out.get(0).getId());
        assertEquals(1, stored.size());
        verify(repository, never()).saveAll(any());
    }

    @Test
    void duplicatesWithinOneBatchCollapse() {
        List<ShoppingItemDTO> out = service.addAll(List.of(req("Eggs"), req("eggs")));

        assertEquals(out.get(0).getId(), out.get(1).getId());
        assertEquals(1, stored.size());
    }

    @Test
    void addingAnItemFromTheBasketBringsItBack() {
        seed("a", "Milk", "DAIRY", true);
        ShoppingItemRequest r = ShoppingItemRequest.builder().name("milk").quantity("2 L").build();

        ShoppingItemDTO out = service.addAll(List.of(r)).get(0);

        assertEquals("a", out.getId());
        assertFalse(out.isChecked());
        assertNull(out.getCheckedAt());
        assertEquals("2 L", out.getQuantity());
        assertEquals(1, stored.size());
    }

    @Test
    void anExistingQuantityIsNotOverwrittenByARepeatAdd() {
        ShoppingItem milk = seed("a", "Milk", "DAIRY", false);
        milk.setQuantity("1 L");

        ShoppingItemRequest r = ShoppingItemRequest.builder().name("Milk").quantity("5 L").note("toned").build();
        ShoppingItemDTO out = service.addAll(List.of(r)).get(0);

        assertEquals("1 L", out.getQuantity());
        assertEquals("toned", out.getNote()); // a missing note is filled in
    }

    @Test
    void checkedOnAddRestoresAnItemStraightIntoTheBasket() {
        ShoppingItemRequest r = ShoppingItemRequest.builder().name("Bananas").checked(true).build();

        ShoppingItemDTO out = service.addAll(List.of(r)).get(0);

        assertTrue(out.isChecked());
        assertNotNull(out.getCheckedAt());
    }

    @Test
    void aFullListRefusesNewItemsButStillAcceptsRepeats() {
        for (int i = 0; i < ShoppingListService.MAX_ITEMS; i++) {
            seed("id" + i, "Item " + i, "OTHER", false);
        }

        assertThrows(IllegalArgumentException.class, () -> service.addAll(List.of(req("One more"))));
        assertEquals("id0", service.addAll(List.of(req("item 0"))).get(0).getId());
    }

    @Test
    void blankNamesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> service.addAll(List.of(req("   "))));
    }

    // ─── Reading ───────────────────────────────────────────────────────────

    @Test
    void listIsInAisleOrderWithToGetBeforeInBasket() {
        seed("1", "Soap", "HOUSEHOLD", false);
        seed("2", "Milk", "DAIRY", true);
        seed("3", "Curd", "DAIRY", false);
        seed("4", "Onions", "PRODUCE", false);

        List<String> names = service.list().stream().map(ShoppingItemDTO::getName).toList();

        assertEquals(List.of("Onions", "Curd", "Milk", "Soap"), names);
    }

    // ─── Editing, checking, removing ───────────────────────────────────────

    @Test
    void updateReplacesFieldsAndKeepsTheCategoryWhenNoneIsSent() {
        seed("a", "Milk", "DAIRY", false);
        ShoppingItemRequest r = ShoppingItemRequest.builder().name("oat milk").quantity(" 1 L ").note("  ").build();

        ShoppingItemDTO out = service.update("a", r);

        assertEquals("Oat milk", out.getName());
        assertEquals("DAIRY", out.getCategory());
        assertEquals("1 L", out.getQuantity());
        assertNull(out.getNote());
    }

    @Test
    void updateMovesAnItemToAnotherCategory() {
        seed("a", "Cream", "DAIRY", false);
        ShoppingItemRequest r = ShoppingItemRequest.builder().name("Cream").category("PERSONAL_CARE").build();

        assertEquals("PERSONAL_CARE", service.update("a", r).getCategory());
    }

    @Test
    void renamingOntoAnotherItemIsRefused() {
        seed("a", "Milk", "DAIRY", false);
        seed("b", "Curd", "DAIRY", false);

        assertThrows(IllegalArgumentException.class, () -> service.update("b", req("MILK")));
    }

    @Test
    void savingAnItemWithItsOwnNameIsNotACollision() {
        seed("a", "Milk", "DAIRY", false);
        assertEquals("Milk", service.update("a", req("milk")).getName());
    }

    @Test
    void checkingStampsTheTimeAndUncheckingClearsIt() {
        seed("a", "Milk", "DAIRY", false);

        ShoppingItemDTO checked = service.setChecked("a", true);
        assertTrue(checked.isChecked());
        assertNotNull(checked.getCheckedAt());

        ShoppingItemDTO unchecked = service.setChecked("a", false);
        assertFalse(unchecked.isChecked());
        assertNull(unchecked.getCheckedAt());
    }

    @Test
    void checkingTwiceIsANoOp() {
        seed("a", "Milk", "DAIRY", true);

        service.setChecked("a", true);

        verify(repository, never()).save(any());
    }

    @Test
    void anotherUsersItemIsNotFound() {
        stored.add(ShoppingItem.builder().id("x").userId("someone-else").name("Milk").category("DAIRY").build());

        assertThrows(IllegalArgumentException.class, () -> service.setChecked("x", true));
        assertThrows(IllegalArgumentException.class, () -> service.update("x", req("Milk")));
        assertThrows(IllegalArgumentException.class, () -> service.delete("x"));
        verify(repository, never()).delete(any());
    }

    @Test
    void deleteRemovesTheItem() {
        ShoppingItem milk = seed("a", "Milk", "DAIRY", false);

        service.delete("a");

        verify(repository).delete(milk);
    }

    @Test
    void clearCheckedRemovesOnlyTheBasketAndReturnsIt() {
        seed("1", "Soap", "HOUSEHOLD", false);
        seed("2", "Milk", "DAIRY", true);
        seed("3", "Onions", "PRODUCE", true);

        List<ShoppingItemDTO> removed = service.clearChecked();

        assertEquals(List.of("Onions", "Milk"), removed.stream().map(ShoppingItemDTO::getName).toList());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Iterable<ShoppingItem>> captor = ArgumentCaptor.forClass(Iterable.class);
        verify(repository).deleteAll(captor.capture());
        assertEquals(2, StreamSupport.stream(captor.getValue().spliterator(), false).count());
    }

    @Test
    void clearingAnEmptyBasketTouchesNothing() {
        seed("1", "Soap", "HOUSEHOLD", false);

        assertTrue(service.clearChecked().isEmpty());
        verify(repository, never()).deleteAll(anyIterable());
    }

    @Test
    void cleanNameCollapsesWhitespaceAndCapitalises() {
        assertEquals("Green tea", ShoppingListService.cleanName("  green \t tea "));
        assertThrows(IllegalArgumentException.class, () -> ShoppingListService.cleanName(" "));
        assertThrows(IllegalArgumentException.class, () -> ShoppingListService.cleanName(null));
    }

    // ─── Memory ────────────────────────────────────────────────────────────

    @Test
    void addsAreRememberedAndCounted() {
        service.addAll(List.of(req("milk")));
        stored.clear();
        service.addAll(List.of(req("Milk")));

        assertNotNull(memory);
        assertEquals(1, memory.getItems().size());
        assertEquals(2, memory.getItems().get(0).getCount());
        assertEquals("DAIRY", memory.getItems().get(0).getCategory());
    }

    @Test
    void movingAnItemTeachesTheListWhereItLives() {
        service.addAll(List.of(req("Milk")));
        String id = stored.get(0).getId();

        service.update(id, ShoppingItemRequest.builder().name("Milk").category("BEVERAGES").build());
        stored.clear();
        ShoppingItemDTO again = service.addAll(List.of(req("milk"))).get(0);

        assertEquals("BEVERAGES", again.getCategory());
        assertTrue(memory.getItems().get(0).isUserFiled());
    }

    @Test
    void anExplicitCategoryStillBeatsMemory() {
        service.addAll(List.of(req("Milk")));
        stored.clear();
        ShoppingItemRequest r = ShoppingItemRequest.builder().name("Milk").category("FROZEN").build();
        assertEquals("FROZEN", service.addAll(List.of(r)).get(0).getCategory());
    }

    @Test
    void restoringIntoTheBasketIsNotCountedAsAnAdd() {
        service.addAll(List.of(ShoppingItemRequest.builder().name("Milk").checked(true).build()));
        verify(memoryRepository, never()).save(any());
    }

    @Test
    void suggestionsLeaveOutWhatIsAlreadyOnTheListAndRankByFrequency() {
        Instant now = Instant.now();
        memory = ShoppingMemory.builder().userId("u1").items(new ArrayList<>(List.of(
                ShoppingMemory.Remembered.builder().name("Milk").category("DAIRY").count(9).lastAdded(now).build(),
                ShoppingMemory.Remembered.builder().name("Bread").category("BAKERY").count(2).lastAdded(now).build(),
                ShoppingMemory.Remembered.builder().name("Saffron").category("SPICES").count(20)
                        .lastAdded(now.minus(Duration.ofDays(180))).build(),
                ShoppingMemory.Remembered.builder().name("Eggs").category("DAIRY").count(5).lastAdded(now).build()))).build();
        seed("a", "Eggs", "DAIRY", false);

        List<String> names = service.suggestions().stream().map(ShoppingSuggestionDTO::getName).toList();

        assertEquals(List.of("Milk", "Bread", "Saffron"), names);
    }

    @Test
    void forgetDropsASuggestion() {
        service.addAll(List.of(req("Milk")));
        service.forget("MILK");
        assertTrue(memory.getItems().isEmpty());
    }

    // ─── Checkout ──────────────────────────────────────────────────────────

    @Test
    void checkoutLogsOneGroceriesExpenseThenEmptiesTheBasket() {
        seed("1", "Milk", "DAIRY", true);
        seed("2", "Onions", "PRODUCE", true);
        seed("3", "Soap", "HOUSEHOLD", false);

        ShoppingCheckoutDTO out = service.checkout(ShoppingCheckoutRequest.builder()
                .amount(new BigDecimal("640")).store("DMart").date("2026-10-06").build());

        ArgumentCaptor<TransactionRequest> tx = ArgumentCaptor.forClass(TransactionRequest.class);
        verify(financeService).createTransaction(tx.capture());
        assertEquals("Groceries · DMart · 2 items", tx.getValue().getDescription());
        assertEquals("Groceries", tx.getValue().getCategory());
        assertEquals("Expense", tx.getValue().getType());
        assertEquals("tx1", out.getTransactionId());
        assertEquals(2, out.getRemoved().size());
        verify(repository).deleteAll(anyIterable());
    }

    @Test
    void checkoutWithoutAnAmountJustClears() {
        seed("1", "Milk", "DAIRY", true);

        ShoppingCheckoutDTO out = service.checkout(ShoppingCheckoutRequest.builder().build());

        assertNull(out.getTransactionId());
        verifyNoInteractions(financeService);
        verify(repository).deleteAll(anyIterable());
    }

    @Test
    void aRefusedExpenseLeavesTheBasketAlone() {
        seed("1", "Milk", "DAIRY", true);
        when(financeService.createTransaction(any())).thenThrow(new IllegalArgumentException("nope"));

        assertThrows(IllegalArgumentException.class, () -> service.checkout(
                ShoppingCheckoutRequest.builder().amount(new BigDecimal("100")).build()));
        verify(repository, never()).deleteAll(anyIterable());
    }

    @Test
    void checkingOutAnEmptyBasketWithNoAmountIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> service.checkout(ShoppingCheckoutRequest.builder().build()));
    }
}
