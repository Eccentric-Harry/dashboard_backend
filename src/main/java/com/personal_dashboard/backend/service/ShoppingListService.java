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
import com.personal_dashboard.backend.model.ShoppingMemory.Remembered;
import com.personal_dashboard.backend.repository.ShoppingItemRepository;
import com.personal_dashboard.backend.repository.ShoppingMemoryRepository;
import com.personal_dashboard.backend.security.UserContext;
import com.personal_dashboard.backend.util.MoneyFlow;
import com.personal_dashboard.backend.util.ShoppingCategories;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The shopping list (/shopping): one flat, per-user collection read back in aisle order.
 *
 * <ul>
 *   <li><b>Filing.</b> An item with no category is filed by {@link ShoppingCategories#guess}; the
 *       user can move it, and an explicit category is always respected.</li>
 *   <li><b>No duplicates.</b> A name is unique per user, case-insensitively. Adding something that
 *       is already to get is a no-op (it just fills a missing quantity or note); adding something
 *       sitting in the basket brings it back onto the list — "buy it again" is typing its name.</li>
 *   <li><b>Checking is not deleting.</b> Items go into the basket and stay there until the user
 *       clears it, so a slipped tap is one tap to undo.</li>
 *   <li><b>Memory.</b> Every add is remembered ({@link ShoppingMemory}): the aisle an item was last
 *       filed in beats the guess next time, and a moved item is the user's choice for good. The
 *       same memory feeds "Buy again" and the add bar's autocomplete.</li>
 *   <li><b>Checkout.</b> "Done shopping" empties the basket and can log the whole shop as one
 *       Groceries expense in Finance — the only place this list touches money.</li>
 * </ul>
 * Every read and write is scoped to the caller; a foreign id is indistinguishable from a missing one.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShoppingListService {

    /** A shopping list is a list, not an archive; past this it is clutter the user should clear. */
    static final int MAX_ITEMS = 300;
    /** Remembered items kept per user; the least recently added fall out first. */
    static final int MAX_REMEMBERED = 400;
    static final int MAX_SUGGESTIONS = 16;
    static final String DEFAULT_CHECKOUT_CATEGORY = "Groceries";
    private static final ZoneId ZONE = ZoneId.systemDefault();

    private final ShoppingItemRepository repository;
    private final ShoppingMemoryRepository memoryRepository;
    private final FinanceService financeService;

    // ─── Reads ────────────────────────────────────────────────────────────

    /** Everything on the list: aisle order, to-get before in-basket within an aisle, then oldest first. */
    public List<ShoppingItemDTO> list() {
        String userId = UserContext.getRequiredUserId();
        return repository.findByUserId(userId).stream()
                .sorted(DISPLAY_ORDER)
                .map(ShoppingListService::toDto)
                .toList();
    }

    /**
     * "Buy again": remembered items that aren't on the list, most-bought and most recent first
     * (frequency decays with a two-week half-life, so last month's one-off fades out).
     */
    public List<ShoppingSuggestionDTO> suggestions() {
        String userId = UserContext.getRequiredUserId();
        Set<String> onList = repository.findByUserId(userId).stream()
                .map(item -> nameKey(item.getName())).collect(Collectors.toSet());
        Instant now = Instant.now();
        return memoryOf(userId).getItems().stream()
                .filter(r -> !onList.contains(nameKey(r.getName())))
                .sorted(Comparator.comparingDouble((Remembered r) -> score(r, now)).reversed()
                        .thenComparing(r -> r.getName().toLowerCase(Locale.ROOT)))
                .limit(MAX_SUGGESTIONS)
                .map(r -> ShoppingSuggestionDTO.builder().name(r.getName()).category(r.getCategory())
                        .count(r.getCount()).lastAdded(r.getLastAdded()).build())
                .toList();
    }

    // ─── Writes ───────────────────────────────────────────────────────────

    /**
     * Adds lines in request order, one read and one write however many there are. The result has one
     * entry per request: the new item, or the existing one it matched (brought back if it was in the basket).
     */
    public List<ShoppingItemDTO> addAll(List<ShoppingItemRequest> requests) {
        String userId = UserContext.getRequiredUserId();
        List<ShoppingItem> existing = repository.findByUserId(userId);
        ShoppingMemory memory = memoryOf(userId);
        boolean remembered = false;
        Map<String, ShoppingItem> byName = new HashMap<>();
        for (ShoppingItem item : existing) {
            byName.put(nameKey(item.getName()), item);
        }

        int size = existing.size();
        List<ShoppingItem> changed = new ArrayList<>();
        List<ShoppingItem> result = new ArrayList<>(requests.size());

        for (ShoppingItemRequest request : requests) {
            String name = cleanName(request.getName());
            boolean toBasket = Boolean.TRUE.equals(request.getChecked());
            ShoppingItem match = byName.get(nameKey(name));

            if (match != null) {
                boolean revived = match.isChecked() && !toBasket;
                if (mergeInto(match, request, toBasket) && !containsInstance(changed, match)) {
                    changed.add(match);
                }
                if (revived) remembered |= remember(memory, match, false);
                result.add(match);
                continue;
            }

            if (size >= MAX_ITEMS) {
                throw new IllegalArgumentException("Your list is full (" + MAX_ITEMS
                        + " items) — clear what's in the basket before adding more");
            }
            ShoppingItem created = ShoppingItem.builder()
                    .name(name)
                    .category(categoryFor(request.getCategory(), name, memory))
                    .quantity(blankToNull(request.getQuantity()))
                    .note(blankToNull(request.getNote()))
                    .checked(toBasket)
                    .checkedAt(toBasket ? Instant.now() : null)
                    .build();
            byName.put(nameKey(name), created);
            // A restore (undo / straight into the basket) isn't a new want — don't count it.
            if (!toBasket) remembered |= remember(memory, created, false);
            changed.add(created);
            result.add(created);
            size++;
        }

        if (!changed.isEmpty()) {
            repository.saveAll(changed);
        }
        if (remembered) {
            memoryRepository.save(memory);
        }
        log.info("Shopping list: {} request(s) → {} written", requests.size(), changed.size());
        return result.stream().map(ShoppingListService::toDto).toList();
    }

    /** Fully replaces an item's name, category, quantity and note. A missing category keeps the current one. */
    public ShoppingItemDTO update(String id, ShoppingItemRequest request) {
        ShoppingItem item = findOwned(id);
        String name = cleanName(request.getName());
        if (!nameKey(name).equals(nameKey(item.getName()))) {
            boolean taken = repository.findByUserId(item.getUserId()).stream()
                    .anyMatch(other -> !other.getId().equals(id) && nameKey(other.getName()).equals(nameKey(name)));
            if (taken) {
                throw new IllegalArgumentException(name + " is already on your list");
            }
        }
        item.setName(name);
        boolean moved = false;
        if (request.getCategory() != null && !request.getCategory().isBlank()) {
            String category = requireCategory(request.getCategory());
            moved = !category.equals(item.getCategory());
            item.setCategory(category);
        }
        item.setQuantity(blankToNull(request.getQuantity()));
        item.setNote(blankToNull(request.getNote()));
        ShoppingItem saved = repository.save(item);
        if (moved) {
            // The user filed it themselves: that aisle wins from now on.
            ShoppingMemory memory = memoryOf(item.getUserId());
            learnAisle(memory, saved);
            memoryRepository.save(memory);
            log.info("Shopping list: learned '{}' lives in {}", saved.getName(), saved.getCategory());
        }
        return toDto(saved);
    }

    /** Idempotent: checking a checked item (a double-tap, a retry) changes nothing. */
    public ShoppingItemDTO setChecked(String id, boolean checked) {
        ShoppingItem item = findOwned(id);
        if (item.isChecked() == checked) {
            return toDto(item);
        }
        item.setChecked(checked);
        item.setCheckedAt(checked ? Instant.now() : null);
        return toDto(repository.save(item));
    }

    public void delete(String id) {
        repository.delete(findOwned(id));
    }

    /** Empties the basket. Returns what was removed so the client can offer an undo. */
    public List<ShoppingItemDTO> clearChecked() {
        String userId = UserContext.getRequiredUserId();
        List<ShoppingItem> basket = repository.findByUserIdAndChecked(userId, true);
        if (basket.isEmpty()) {
            return List.of();
        }
        repository.deleteAll(basket);
        log.info("Shopping list: cleared {} item(s) from the basket", basket.size());
        return basket.stream().sorted(DISPLAY_ORDER).map(ShoppingListService::toDto).toList();
    }

    /**
     * "Done shopping": empties the basket and, given an amount, logs the shop as one expense —
     * "Groceries · DMart · 12 items". The basket is emptied only after the ledger accepted the row,
     * so a refused amount leaves everything where it was.
     */
    public ShoppingCheckoutDTO checkout(ShoppingCheckoutRequest request) {
        String userId = UserContext.getRequiredUserId();
        List<ShoppingItem> basket = repository.findByUserIdAndChecked(userId, true);
        String transactionId = null;
        BigDecimal amount = request.getAmount();
        if (amount != null && amount.signum() > 0) {
            String category = hasText(request.getCategory()) ? request.getCategory().trim() : DEFAULT_CHECKOUT_CATEGORY;
            TransactionDTO tx = financeService.createTransaction(TransactionRequest.builder()
                    .description(checkoutDescription(category, blankToNull(request.getStore()), basket.size()))
                    .amount(amount)
                    .category(category)
                    .type(MoneyFlow.EXPENSE)
                    .date(hasText(request.getDate()) ? request.getDate() : LocalDate.now(ZONE).toString())
                    .build());
            transactionId = tx.getId();
        } else if (basket.isEmpty()) {
            throw new IllegalArgumentException("The basket is empty");
        }
        if (!basket.isEmpty()) {
            repository.deleteAll(basket);
        }
        log.info("Shopping list: checkout of {} item(s), logged {}", basket.size(), amount);
        return ShoppingCheckoutDTO.builder()
                .removed(basket.stream().sorted(DISPLAY_ORDER).map(ShoppingListService::toDto).toList())
                .transactionId(transactionId)
                .build();
    }

    /** Drops a name from "Buy again" and autocomplete. Unknown names are fine (idempotent). */
    public void forget(String name) {
        String userId = UserContext.getRequiredUserId();
        ShoppingMemory memory = memoryOf(userId);
        if (memory.getItems().removeIf(r -> nameKey(r.getName()).equals(nameKey(name)))) {
            memoryRepository.save(memory);
        }
    }

    // ─── Helpers ──────────────────────────────────────────────────────────

    static String checkoutDescription(String category, String store, int count) {
        StringBuilder text = new StringBuilder(category);
        if (store != null) text.append(" · ").append(store);
        if (count > 0) text.append(" · ").append(count).append(count == 1 ? " item" : " items");
        return text.toString();
    }

    /** Frequency with a two-week half-life on how long ago it was last added. */
    static double score(Remembered r, Instant now) {
        double days = r.getLastAdded() == null ? 365 : Math.max(0, Duration.between(r.getLastAdded(), now).toHours() / 24.0);
        return r.getCount() * Math.pow(0.5, days / 14.0);
    }

    private ShoppingMemory memoryOf(String userId) {
        return memoryRepository.findFirstByUserId(userId)
                .orElseGet(() -> ShoppingMemory.builder().userId(userId).items(new ArrayList<>()).build());
    }

    /** Counts an add and remembers where the item went. Returns true (memory changed). */
    static boolean remember(ShoppingMemory memory, ShoppingItem item, boolean userFiled) {
        Remembered entry = find(memory, item.getName());
        if (entry == null) {
            entry = Remembered.builder().name(item.getName()).category(item.getCategory()).build();
            memory.getItems().add(entry);
        }
        entry.setName(item.getName());
        if (!entry.isUserFiled() || userFiled) entry.setCategory(item.getCategory());
        entry.setUserFiled(entry.isUserFiled() || userFiled);
        entry.setCount(entry.getCount() + 1);
        entry.setLastAdded(Instant.now());
        trim(memory);
        return true;
    }

    /** The user moved the item: remember the aisle as theirs, without counting an add. */
    static void learnAisle(ShoppingMemory memory, ShoppingItem item) {
        Remembered entry = find(memory, item.getName());
        if (entry == null) {
            entry = Remembered.builder().name(item.getName()).count(0).lastAdded(Instant.now()).build();
            memory.getItems().add(entry);
        }
        entry.setCategory(item.getCategory());
        entry.setUserFiled(true);
        trim(memory);
    }

    private static Remembered find(ShoppingMemory memory, String name) {
        String key = nameKey(name);
        return memory.getItems().stream().filter(r -> nameKey(r.getName()).equals(key)).findFirst().orElse(null);
    }

    private static void trim(ShoppingMemory memory) {
        if (memory.getItems().size() <= MAX_REMEMBERED) return;
        memory.getItems().sort(Comparator.comparing((Remembered r) -> r.getLastAdded() == null ? Instant.EPOCH : r.getLastAdded()).reversed());
        memory.setItems(new ArrayList<>(memory.getItems().subList(0, MAX_REMEMBERED)));
    }

    private static final Comparator<ShoppingItem> DISPLAY_ORDER = Comparator
            .comparingInt((ShoppingItem i) -> ShoppingCategories.orderOf(i.getCategory()))
            .thenComparing(ShoppingItem::isChecked)
            .thenComparing(i -> i.getCreatedAt() == null ? Instant.EPOCH : i.getCreatedAt())
            .thenComparing(i -> i.getName() == null ? "" : i.getName().toLowerCase(Locale.ROOT));

    /**
     * Folds a request that names an existing item into it. Returns whether anything changed: a
     * basket item comes back onto the list (unless the request is itself putting it in the basket),
     * and blank quantity/note fill in from the request without overwriting what the user already set.
     */
    private static boolean mergeInto(ShoppingItem match, ShoppingItemRequest request, boolean toBasket) {
        boolean changed = false;
        if (match.isChecked() && !toBasket) {
            match.setChecked(false);
            match.setCheckedAt(null);
            // Bought-again with a fresh quantity: the new one is what's wanted now.
            if (hasText(request.getQuantity())) {
                match.setQuantity(request.getQuantity().trim());
            }
            changed = true;
        } else if (!match.isChecked() && toBasket) {
            match.setChecked(true);
            match.setCheckedAt(Instant.now());
            changed = true;
        }
        if (!hasText(match.getQuantity()) && hasText(request.getQuantity())) {
            match.setQuantity(request.getQuantity().trim());
            changed = true;
        }
        if (!hasText(match.getNote()) && hasText(request.getNote())) {
            match.setNote(request.getNote().trim());
            changed = true;
        }
        return changed;
    }

    private ShoppingItem findOwned(String id) {
        String userId = UserContext.getRequiredUserId();
        return repository.findById(id)
                .filter(item -> userId.equals(item.getUserId()))
                .orElseThrow(() -> new IllegalArgumentException("Shopping item not found: " + id));
    }

    /** An explicit aisle, else where this item was last filed, else the guess from its name. */
    private static String categoryFor(String requested, String name, ShoppingMemory memory) {
        if (hasText(requested)) return requireCategory(requested);
        Remembered known = find(memory, name);
        if (known != null && ShoppingCategories.isValid(known.getCategory())) return known.getCategory();
        return ShoppingCategories.guess(name);
    }

    private static String requireCategory(String requested) {
        String key = ShoppingCategories.normalize(requested);
        if (key == null) {
            throw new IllegalArgumentException("Unknown category: " + requested);
        }
        return key;
    }

    /** Trims, collapses inner whitespace and capitalises the first letter: "  green   tea " → "Green tea". */
    static String cleanName(String raw) {
        String name = raw == null ? "" : raw.trim().replaceAll("\\s+", " ");
        if (name.isEmpty()) {
            throw new IllegalArgumentException("Name is required");
        }
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    private static String nameKey(String name) {
        return name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
    }

    /** Identity, not equals(): a document's fields change as a batch is folded in. */
    private static boolean containsInstance(List<ShoppingItem> items, ShoppingItem item) {
        return items.stream().anyMatch(candidate -> candidate == item);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String blankToNull(String value) {
        return hasText(value) ? value.trim() : null;
    }

    private static ShoppingItemDTO toDto(ShoppingItem item) {
        return ShoppingItemDTO.builder()
                .id(item.getId())
                .name(item.getName())
                .category(item.getCategory())
                .quantity(item.getQuantity())
                .note(item.getNote())
                .checked(item.isChecked())
                .checkedAt(item.getCheckedAt())
                .createdAt(item.getCreatedAt())
                .build();
    }
}
