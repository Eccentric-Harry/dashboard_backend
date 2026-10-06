package com.personal_dashboard.backend.service;

import com.personal_dashboard.backend.dto.LinkPreviewDTO;
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
import com.personal_dashboard.backend.service.showcase.SafeWebClient;
import com.personal_dashboard.backend.util.MoneyFlow;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The wishlist (/shopping, Wishlist tab): product cards with where they'd come from, a photo
 * referenced by URL, and two ways into Finance —
 * <ul>
 *   <li><b>Bought it</b> — logs the purchase as spending. A wish with a savings goal goes
 *       through {@link SavingsGoalService#buy}, which releases what was saved and keeps the
 *       purchase off the monthly budget; otherwise it is a plain Expense.</li>
 *   <li><b>Save up for it</b> — creates a "Saving for" goal from the wish (name, price, photo),
 *       so the plan, the pace and the jar all live where the money does.</li>
 * </ul>
 * A goal bought or archived from /finance is reflected here on the next read. Nothing ever
 * moves money on its own, and nothing judges: letting a wish go is a first-class outcome.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WishlistService {

    /** Open wishes. Past this it stops being a shortlist. */
    static final int MAX_OPEN = 100;
    static final String DEFAULT_CATEGORY = "Shopping";
    /** SavingsGoalRequest caps names at 40 characters. */
    private static final int GOAL_NAME_MAX = 40;
    private static final ZoneId ZONE = ZoneId.systemDefault();

    private final WishlistItemRepository repository;
    private final SavingsGoalRepository savingsGoalRepository;
    private final SavingsGoalService savingsGoalService;
    private final GoalShowcaseService goalShowcaseService;
    private final FinanceService financeService;
    private final ProductLinkPreviewer previewer;

    // ─── Reads ────────────────────────────────────────────────────────────

    /** Every wish: open ones (needs first, then newest), then bought / let go (most recent first). */
    public List<WishlistItemDTO> list() {
        String userId = UserContext.getRequiredUserId();
        List<WishlistItem> items = repository.findByUserId(userId);
        reconcileGoals(userId, items);
        return items.stream().sorted(DISPLAY_ORDER).map(WishlistService::toDto).toList();
    }

    /** Reads a product link to prefill the add form. Never fails on a blocked page — the link alone still says something. */
    public LinkPreviewDTO preview(String url) {
        LinkPreview p = previewer.preview(url);
        return LinkPreviewDTO.builder()
                .url(p.url()).title(p.title()).imageUrl(p.imageUrl()).store(p.store())
                .price(p.price() == null ? null : p.price().doubleValue())
                .fetched(p.fetched())
                .build();
    }

    // ─── Writes ───────────────────────────────────────────────────────────

    public WishlistItemDTO create(WishlistItemRequest request) {
        String userId = UserContext.getRequiredUserId();
        long open = repository.findByUserId(userId).stream().filter(w -> WishlistItem.WANTED.equals(w.getStatus())).count();
        if (open >= MAX_OPEN) {
            throw new IllegalArgumentException("Your wishlist has " + MAX_OPEN + " things on it — let some go first");
        }
        WishlistItem item = WishlistItem.builder().status(WishlistItem.WANTED).build();
        apply(item, request);
        item.setFirstPrice(item.getPrice());
        if (item.getPrice() != null) item.setPriceCheckedAt(Instant.now());
        WishlistItem saved = repository.save(item);
        log.info("Wishlist: added {} '{}' ({}, {})", saved.getId(), saved.getName(), saved.getStore(), saved.getPrice());
        return toDto(saved);
    }

    public WishlistItemDTO update(String id, WishlistItemRequest request) {
        WishlistItem item = findOwned(id);
        BigDecimal before = item.getPrice();
        apply(item, request);
        if (item.getPrice() != null && (before == null || before.compareTo(item.getPrice()) != 0)) {
            item.setPriceCheckedAt(Instant.now());
            if (item.getFirstPrice() == null) item.setFirstPrice(item.getPrice());
        }
        return toDto(repository.save(item));
    }

    /**
     * Re-reads the product page: a new price (the first one stays as the baseline) and, if the
     * card had none, a photo. A page that can't be read changes nothing.
     */
    public WishlistItemDTO refresh(String id) {
        WishlistItem item = findOwned(id);
        if (item.getUrl() == null) {
            throw new IllegalArgumentException("Add the product's link to check its price");
        }
        LinkPreview p = previewer.preview(item.getUrl());
        if (!p.fetched()) {
            throw new IllegalArgumentException((item.getStore() != null ? item.getStore() : "The store")
                    + " didn't let us read the page — check the price there");
        }
        if (p.price() != null) {
            if (item.getFirstPrice() == null) item.setFirstPrice(item.getPrice() != null ? item.getPrice() : p.price());
            item.setPrice(p.price());
        }
        if (item.getImageUrl() == null && p.imageUrl() != null) item.setImageUrl(p.imageUrl());
        item.setPriceCheckedAt(Instant.now());
        WishlistItem saved = repository.save(item);
        log.info("Wishlist: refreshed {} '{}' → price {}", id, item.getName(), p.price());
        return toDto(saved);
    }

    /** Logs the purchase in Finance and closes the wish. */
    public WishlistItemDTO buy(String id, WishlistBuyRequest request) {
        WishlistItem item = findOwned(id);
        if (!WishlistItem.WANTED.equals(item.getStatus())) {
            throw new IllegalArgumentException(item.getName() + " is already " + label(item.getStatus()));
        }
        String date = request.getDate() != null ? request.getDate() : LocalDate.now(ZONE).toString();
        String category = request.getCategory() != null && !request.getCategory().isBlank()
                ? request.getCategory().trim() : DEFAULT_CATEGORY;
        String description = describe(item);

        SavingsGoal goal = linkedOpenGoal(item);
        if (goal != null) {
            // The goal's own purchase flow: saved money comes back, the purchase stays off the budget.
            savingsGoalService.buy(goal.getId(), GoalPurchaseRequest.builder()
                    .price(request.getPrice()).category(category).description(truncate(description, 80)).date(date).build());
        } else {
            TransactionDTO tx = financeService.createTransaction(TransactionRequest.builder()
                    .description(description)
                    .amount(request.getPrice())
                    .category(category)
                    .type(MoneyFlow.EXPENSE)
                    .date(date)
                    .build());
            item.setTransactionId(tx.getId());
        }
        item.setStatus(WishlistItem.BOUGHT);
        item.setBoughtOn(date);
        item.setBoughtFor(request.getPrice());
        item.setClosedAt(Instant.now());
        WishlistItem saved = repository.save(item);
        log.info("Wishlist: bought {} '{}' for {} ({})", id, item.getName(), request.getPrice(),
                goal != null ? "from goal " + goal.getId() : "logged as spending");
        return toDto(saved);
    }

    /**
     * Turns the wish into a "Saving for" goal on /finance: the name, the price as the target, and
     * the card's photo as the goal's first showcase photo (best effort — the goal stands without it).
     */
    public WishlistItemDTO saveFor(String id) {
        WishlistItem item = findOwned(id);
        if (!WishlistItem.WANTED.equals(item.getStatus())) {
            throw new IllegalArgumentException(item.getName() + " is already " + label(item.getStatus()));
        }
        if (linkedOpenGoal(item) != null) {
            return toDto(item);
        }
        if (item.getPrice() == null || item.getPrice().signum() <= 0) {
            throw new IllegalArgumentException("Add a price first — it becomes the goal's target");
        }
        SavingsGoalDTO goal = savingsGoalService.create(SavingsGoalRequest.builder()
                .name(truncate(item.getName(), GOAL_NAME_MAX))
                .kind(SavingsGoalService.DEFAULT_KIND)
                .icon("bag")
                .listPrice(item.getPrice())
                .build());
        String photoSource = item.getImageUrl() != null ? item.getImageUrl() : item.getUrl();
        if (photoSource != null) {
            try {
                goalShowcaseService.find(goal.getId(), photoSource);
            } catch (RuntimeException e) {
                log.info("Wishlist: goal {} created without a photo: {}", goal.getId(), e.getMessage());
            }
        }
        item.setSavingsGoalId(goal.getId());
        WishlistItem saved = repository.save(item);
        log.info("Wishlist: saving for {} '{}' as goal {}", id, item.getName(), goal.getId());
        return toDto(saved);
    }

    public WishlistItemDTO letGo(String id) {
        WishlistItem item = findOwned(id);
        if (!WishlistItem.WANTED.equals(item.getStatus())) {
            throw new IllegalArgumentException(item.getName() + " is already " + label(item.getStatus()));
        }
        item.setStatus(WishlistItem.LET_GO);
        item.setClosedAt(Instant.now());
        return toDto(repository.save(item));
    }

    /**
     * Back onto the wishlist. Reopening a bought wish does not touch the ledger — the purchase row
     * stays as history, exactly like reopening a bought savings goal.
     */
    public WishlistItemDTO reopen(String id) {
        WishlistItem item = findOwned(id);
        item.setStatus(WishlistItem.WANTED);
        item.setBoughtOn(null);
        item.setBoughtFor(null);
        item.setTransactionId(null);
        item.setClosedAt(null);
        return toDto(repository.save(item));
    }

    public void delete(String id) {
        repository.delete(findOwned(id));
    }

    // ─── Helpers ──────────────────────────────────────────────────────────

    private static final Comparator<WishlistItem> DISPLAY_ORDER = Comparator
            .comparingInt((WishlistItem w) -> WishlistItem.WANTED.equals(w.getStatus()) ? 0 : 1)
            .thenComparingInt(w -> WishlistItem.WANTED.equals(w.getStatus()) && WishlistItem.NEED.equals(w.getPriority()) ? 0 : 1)
            .thenComparing((WishlistItem w) -> WishlistItem.WANTED.equals(w.getStatus())
                    ? orEpoch(w.getCreatedAt()) : orEpoch(w.getClosedAt()), Comparator.reverseOrder());

    private static Instant orEpoch(Instant instant) {
        return instant == null ? Instant.EPOCH : instant;
    }

    /**
     * Reflects what happened to linked goals on /finance: a goal bought there closes the wish with
     * the goal's price; a goal archived there is simply unlinked. Saved only when something changed.
     */
    private void reconcileGoals(String userId, List<WishlistItem> items) {
        if (items.stream().noneMatch(w -> w.getSavingsGoalId() != null)) return;
        Map<String, SavingsGoal> goals = savingsGoalRepository.findByUserId(userId).stream()
                .collect(Collectors.toMap(SavingsGoal::getId, Function.identity(), (a, b) -> a));
        List<WishlistItem> changed = new ArrayList<>();
        for (WishlistItem item : items) {
            if (item.getSavingsGoalId() == null) continue;
            SavingsGoal goal = goals.get(item.getSavingsGoalId());
            if (goal == null || SavingsGoal.ARCHIVED.equals(goal.getStatus())) {
                item.setSavingsGoalId(null);
                changed.add(item);
            } else if (SavingsGoal.BOUGHT.equals(goal.getStatus()) && WishlistItem.WANTED.equals(item.getStatus())) {
                item.setStatus(WishlistItem.BOUGHT);
                item.setBoughtOn(goal.getBoughtOn());
                item.setBoughtFor(goal.getBoughtFor());
                item.setClosedAt(Instant.now());
                changed.add(item);
            }
        }
        if (!changed.isEmpty()) repository.saveAll(changed);
    }

    /** The wish's goal if it can still be bought through (active or paused). */
    private SavingsGoal linkedOpenGoal(WishlistItem item) {
        if (item.getSavingsGoalId() == null) return null;
        return savingsGoalRepository.findById(item.getSavingsGoalId())
                .filter(g -> item.getUserId() != null && item.getUserId().equals(g.getUserId()))
                .filter(g -> SavingsGoal.ACTIVE.equals(g.getStatus()) || SavingsGoal.PAUSED.equals(g.getStatus()))
                .orElse(null);
    }

    private static void apply(WishlistItem item, WishlistItemRequest request) {
        String name = request.getName().trim().replaceAll("\\s+", " ");
        if (name.isEmpty()) throw new IllegalArgumentException("Name is required");
        item.setName(name);
        String url = blankToNull(request.getUrl());
        item.setUrl(url == null ? null : SafeWebClient.parseLink(url).toString());
        String store = blankToNull(request.getStore());
        item.setStore(store);
        String image = blankToNull(request.getImageUrl());
        item.setImageUrl(image == null ? null : SafeWebClient.parseLink(image).toString());
        item.setPrice(request.getPrice() != null && request.getPrice().signum() > 0 ? request.getPrice() : null);
        item.setPriority(request.getPriority() != null ? request.getPriority() : WishlistItem.WANT);
        item.setNote(blankToNull(request.getNote()));
    }

    private WishlistItem findOwned(String id) {
        String userId = UserContext.getRequiredUserId();
        return repository.findById(id)
                .filter(item -> userId.equals(item.getUserId()))
                .orElseThrow(() -> new IllegalArgumentException("Wish not found: " + id));
    }

    /** "Nike Pegasus 41 · Amazon" — what the ledger row says. */
    private static String describe(WishlistItem item) {
        String name = truncate(item.getName(), 60);
        return item.getStore() != null ? name + " · " + item.getStore() : name;
    }

    private static String label(String status) {
        return WishlistItem.BOUGHT.equals(status) ? "bought" : WishlistItem.LET_GO.equals(status) ? "let go" : "on your list";
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max - 1).trim() + "…";
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static Double num(BigDecimal value) {
        return value == null ? null : value.doubleValue();
    }

    static WishlistItemDTO toDto(WishlistItem w) {
        return WishlistItemDTO.builder()
                .id(w.getId()).name(w.getName()).url(w.getUrl()).store(w.getStore()).imageUrl(w.getImageUrl())
                .price(num(w.getPrice())).firstPrice(num(w.getFirstPrice())).priceCheckedAt(w.getPriceCheckedAt())
                .priority(w.getPriority()).note(w.getNote()).status(w.getStatus())
                .boughtOn(w.getBoughtOn()).boughtFor(num(w.getBoughtFor())).transactionId(w.getTransactionId())
                .savingsGoalId(w.getSavingsGoalId()).closedAt(w.getClosedAt()).createdAt(w.getCreatedAt())
                .build();
    }
}
