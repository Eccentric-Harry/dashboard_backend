package com.personal_dashboard.backend.service;

import com.personal_dashboard.backend.dto.SavingsGoalDTO;
import com.personal_dashboard.backend.dto.request.GoalMoneyRequest;
import com.personal_dashboard.backend.dto.request.GoalPurchaseRequest;
import com.personal_dashboard.backend.dto.request.SavingsGoalRequest;
import com.personal_dashboard.backend.dto.request.TransactionRequest;
import com.personal_dashboard.backend.model.SavingsGoal;
import com.personal_dashboard.backend.repository.SavingsGoalRepository;
import com.personal_dashboard.backend.security.UserContext;
import com.personal_dashboard.backend.service.FinanceService.GoalTally;
import com.personal_dashboard.backend.util.MoneyFlow;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Savings goals ("Saving for" on /finance). A goal stores only its plan; its money lives in
 * the ledger as ordinary rows carrying {@code goalId}, written here through
 * {@link FinanceService} so the balance, the day totals and the budget stay in one place:
 * <ul>
 *   <li><b>Set aside</b> — a Transfer OUT in {@code Savings}: the spendable balance drops, the
 *       goal grows, spending and the budget never see it.</li>
 *   <li><b>Take out</b> — a Transfer IN in {@code Savings}: the reverse.</li>
 *   <li><b>Buy</b> — a Transfer IN of what the goal holds (up to the price) plus the purchase
 *       as an Expense linked to the goal, which {@link MoneyFlow#countsTowardBudget} keeps off
 *       the monthly budget. The balance drops by the price exactly once.</li>
 * </ul>
 * Nothing here ever moves money on its own — every row is a user action.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SavingsGoalService {

    public static final String SAVINGS_CATEGORY = "Savings";
    public static final String DEFAULT_PURCHASE_CATEGORY = "Shopping";
    public static final String DEFAULT_KIND = "PURCHASE";
    public static final String OPEN_KIND = "OPEN";
    /** Goals that aren't archived. More than this stops being a plan and becomes a list. */
    static final int MAX_LIVE_GOALS = 12;

    private static final ZoneId ZONE = ZoneId.systemDefault();
    private static final Set<String> LIVE = Set.of(SavingsGoal.ACTIVE, SavingsGoal.PAUSED, SavingsGoal.BOUGHT);

    private final SavingsGoalRepository repository;
    private final FinanceService financeService;

    // ─── Reads ────────────────────────────────────────────────────────────

    public List<SavingsGoalDTO> list(boolean includeArchived) {
        String userId = UserContext.getRequiredUserId();
        Map<String, GoalTally> tallies = financeService.tallyGoals();
        return repository.findByUserId(userId).stream()
                .filter(g -> includeArchived || !SavingsGoal.ARCHIVED.equals(g.getStatus()))
                .sorted(Comparator.comparingInt(SavingsGoal::getPriority)
                        .thenComparing(g -> g.getCreatedAt() == null ? "" : g.getCreatedAt().toString()))
                .map(g -> toDto(g, tallies.get(g.getId())))
                .toList();
    }

    // ─── Plan CRUD ────────────────────────────────────────────────────────

    public SavingsGoalDTO create(SavingsGoalRequest request) {
        String userId = UserContext.getRequiredUserId();
        List<SavingsGoal> existing = repository.findByUserId(userId);
        long live = existing.stream().filter(g -> LIVE.contains(g.getStatus())).count();
        if (live >= MAX_LIVE_GOALS) {
            throw new IllegalArgumentException("You already have " + MAX_LIVE_GOALS
                    + " goals — archive one before adding another");
        }
        SavingsGoal goal = SavingsGoal.builder()
                .status(SavingsGoal.ACTIVE)
                .priority(existing.stream().mapToInt(SavingsGoal::getPriority).max().orElse(-1) + 1)
                .startDate(today())
                .build();
        apply(goal, request);
        SavingsGoal saved = repository.save(goal);
        log.info("Created savings goal {} '{}' (target={}, by={})", saved.getId(), saved.getName(),
                saved.getTargetAmount(), saved.getTargetDate());
        return toDto(saved, null);
    }

    public SavingsGoalDTO update(String id, SavingsGoalRequest request) {
        SavingsGoal goal = findOwned(id);
        apply(goal, request);
        if (request.getStatus() != null) {
            if (SavingsGoal.ACTIVE.equals(request.getStatus()) && SavingsGoal.BOUGHT.equals(goal.getStatus())) {
                // Reopening a bought goal: the purchase row stays in the ledger as history.
                goal.setBoughtOn(null);
                goal.setBoughtFor(null);
            }
            goal.setStatus(request.getStatus());
        }
        SavingsGoal saved = repository.save(goal);
        log.info("Updated savings goal {} '{}' (status={})", id, saved.getName(), saved.getStatus());
        return withTally(saved);
    }

    /** Soft-delete. With {@code release}, whatever is still in the goal goes back to the balance first. */
    public SavingsGoalDTO archive(String id, boolean release) {
        SavingsGoal goal = findOwned(id);
        BigDecimal held = tallyOf(id).saved();
        if (release && held.signum() > 0) {
            financeService.createTransaction(transfer(goal, MoneyFlow.IN, held, today(),
                    describe(MoneyFlow.IN, goal, "goal archived")));
        }
        goal.setStatus(SavingsGoal.ARCHIVED);
        SavingsGoal saved = repository.save(goal);
        log.info("Archived savings goal {} '{}' (released {})", id, goal.getName(), release ? held : BigDecimal.ZERO);
        return withTally(saved);
    }

    // ─── Money ────────────────────────────────────────────────────────────

    public SavingsGoalDTO setAside(String id, GoalMoneyRequest request) {
        SavingsGoal goal = findOwned(id);
        requireOpen(goal, "set money aside for");
        String note = blankToNull(request.getNote());
        financeService.createTransaction(transfer(goal, MoneyFlow.OUT, request.getAmount(),
                dateOr(request.getDate()), describe(MoneyFlow.OUT, goal, note)));
        log.info("Set aside {} for goal {} '{}'", request.getAmount(), id, goal.getName());
        return withTally(goal);
    }

    public SavingsGoalDTO takeOut(String id, GoalMoneyRequest request) {
        SavingsGoal goal = findOwned(id);
        BigDecimal held = tallyOf(id).saved();
        if (request.getAmount().compareTo(held) > 0) {
            throw new IllegalArgumentException("Only " + held.toPlainString() + " is set aside for " + goal.getName());
        }
        String note = blankToNull(request.getNote());
        financeService.createTransaction(transfer(goal, MoneyFlow.IN, request.getAmount(), dateOr(request.getDate()),
                describe(MoneyFlow.IN, goal, note)));
        log.info("Took {} out of goal {} '{}' ({})", request.getAmount(), id, goal.getName(), note);
        return withTally(goal);
    }

    /**
     * Records the purchase: what the goal holds (up to the price) comes back to the balance,
     * then the purchase leaves it as spending that never counts against the budget. Paying
     * more than was saved is fine — the difference simply comes from the balance.
     */
    public SavingsGoalDTO buy(String id, GoalPurchaseRequest request) {
        SavingsGoal goal = findOwned(id);
        requireOpen(goal, "buy");
        String date = dateOr(request.getDate());
        BigDecimal price = request.getPrice();
        BigDecimal fromGoal = tallyOf(id).saved().min(price);
        if (fromGoal.signum() > 0) {
            financeService.createTransaction(transfer(goal, MoneyFlow.IN, fromGoal, date,
                    describe(MoneyFlow.IN, goal, "paid for it")));
        }
        String category = blankToNull(request.getCategory());
        String description = blankToNull(request.getDescription());
        financeService.createTransaction(TransactionRequest.builder()
                .description(description != null ? description : goal.getName())
                .amount(price)
                .category(category != null ? category.trim() : DEFAULT_PURCHASE_CATEGORY)
                .type(MoneyFlow.EXPENSE)
                .date(date)
                .goalId(goal.getId())
                .build());
        goal.setStatus(SavingsGoal.BOUGHT);
        goal.setBoughtOn(date);
        goal.setBoughtFor(price);
        SavingsGoal saved = repository.save(goal);
        log.info("Bought goal {} '{}' for {} ({} from the goal)", id, goal.getName(), price, fromGoal);
        return withTally(saved);
    }

    // ─── Helpers ──────────────────────────────────────────────────────────

    private SavingsGoal findOwned(String id) {
        String userId = UserContext.getRequiredUserId();
        return repository.findById(id)
                .filter(goal -> userId.equals(goal.getUserId()))
                .orElseThrow(() -> new IllegalArgumentException("Savings goal not found: " + id));
    }

    private static void requireOpen(SavingsGoal goal, String action) {
        if (SavingsGoal.ARCHIVED.equals(goal.getStatus()) || SavingsGoal.BOUGHT.equals(goal.getStatus())) {
            throw new IllegalArgumentException("Can't " + action + " " + goal.getName() + " — it's "
                    + goal.getStatus().toLowerCase());
        }
    }

    private static void apply(SavingsGoal goal, SavingsGoalRequest request) {
        String kind = request.getKind() == null ? DEFAULT_KIND : request.getKind();
        goal.setName(request.getName().trim());
        goal.setKind(kind);
        goal.setIcon(blankToNull(request.getIcon()));
        goal.setColor(blankToNull(request.getColor()));
        goal.setListPrice(request.getListPrice());
        goal.setExchangeValue(request.getExchangeValue());
        goal.setCardOffer(request.getCardOffer());
        goal.setTargetAmount(targetOf(request, kind));
        goal.setTargetDate(blankToNull(request.getTargetDate()));
        goal.setPlannedMonthly(request.getPlannedMonthly());
        goal.setKeptAt(request.getKeptAt() == null ? null : blankToNull(request.getKeptAt().trim()));
        if (request.getStartDate() != null) goal.setStartDate(request.getStartDate());
        if (request.getPriority() != null) goal.setPriority(request.getPriority());
        if (goal.getTargetDate() != null && goal.getStartDate() != null
                && goal.getTargetDate().compareTo(goal.getStartDate()) < 0) {
            throw new IllegalArgumentException("The target date is before the goal starts");
        }
    }

    /** An explicit target wins; a purchase can derive it from its price; only OPEN goals may have none. */
    static BigDecimal targetOf(SavingsGoalRequest request, String kind) {
        if (request.getTargetAmount() != null) {
            return request.getTargetAmount();
        }
        if (request.getListPrice() != null && request.getListPrice().signum() > 0) {
            BigDecimal net = request.getListPrice()
                    .subtract(zeroIfNull(request.getExchangeValue()))
                    .subtract(zeroIfNull(request.getCardOffer()));
            if (net.signum() <= 0) {
                throw new IllegalArgumentException("The exchange and offer cover the whole price — nothing to save");
            }
            return net;
        }
        if (OPEN_KIND.equals(kind)) {
            return null;
        }
        throw new IllegalArgumentException("Set how much this goal needs");
    }

    /**
     * The ledger line a goal's money row carries: which way it went and the goal, then the
     * why — "To Safety net · September leftover", "From iPhone 18 Pro · paid for it". Short,
     * so it reads in the ledger next to the Savings chip, the way "Sent home to Amma" does.
     */
    static String describe(String direction, SavingsGoal goal, String note) {
        String base = (MoneyFlow.IN.equals(direction) ? "From " : "To ") + goal.getName();
        return note == null || note.isBlank() ? base : base + " · " + note.trim();
    }

    private static TransactionRequest transfer(SavingsGoal goal, String direction, BigDecimal amount, String date,
            String description) {
        return TransactionRequest.builder()
                .description(description.length() > 80 ? description.substring(0, 80) : description)
                .amount(amount)
                .category(SAVINGS_CATEGORY)
                .type(MoneyFlow.TRANSFER)
                .direction(direction)
                .date(date)
                .goalId(goal.getId())
                .build();
    }

    private GoalTally tallyOf(String id) {
        GoalTally tally = financeService.tallyGoals().get(id);
        return tally != null ? tally : new GoalTally();
    }

    private SavingsGoalDTO withTally(SavingsGoal goal) {
        return toDto(goal, financeService.tallyGoals().get(goal.getId()));
    }

    static SavingsGoalDTO toDto(SavingsGoal goal, GoalTally tally) {
        GoalTally t = tally != null ? tally : new GoalTally();
        return SavingsGoalDTO.builder()
                .id(goal.getId())
                .name(goal.getName())
                .kind(goal.getKind() == null ? DEFAULT_KIND : goal.getKind())
                .icon(goal.getIcon())
                .color(goal.getColor())
                .targetAmount(toDouble(goal.getTargetAmount()))
                .listPrice(toDouble(goal.getListPrice()))
                .exchangeValue(toDouble(goal.getExchangeValue()))
                .cardOffer(toDouble(goal.getCardOffer()))
                .targetDate(goal.getTargetDate())
                .plannedMonthly(toDouble(goal.getPlannedMonthly()))
                .keptAt(goal.getKeptAt())
                .startDate(goal.getStartDate())
                .priority(goal.getPriority())
                .status(goal.getStatus() == null ? SavingsGoal.ACTIVE : goal.getStatus())
                .boughtOn(goal.getBoughtOn())
                .boughtFor(toDouble(goal.getBoughtFor()))
                .saved(t.saved().doubleValue())
                .setAside(t.setAside().doubleValue())
                .takenOut(t.takenOut().doubleValue())
                .spent(t.spent().doubleValue())
                .contributions(t.contributions())
                .firstContributionDate(t.firstDate())
                .lastContributionDate(t.lastDate())
                .build();
    }

    private static String today() {
        return LocalDate.now(ZONE).toString();
    }

    private static String dateOr(String date) {
        return date == null || date.isBlank() ? today() : date;
    }

    private static Double toDouble(BigDecimal value) {
        return value == null ? null : value.doubleValue();
    }

    private static BigDecimal zeroIfNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
