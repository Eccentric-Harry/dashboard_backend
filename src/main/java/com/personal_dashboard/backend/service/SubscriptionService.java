package com.personal_dashboard.backend.service;

import com.personal_dashboard.backend.dto.SubscriptionDTO;
import com.personal_dashboard.backend.dto.TransactionDTO;
import com.personal_dashboard.backend.dto.request.SubscriptionPaymentRequest;
import com.personal_dashboard.backend.dto.request.SubscriptionRequest;
import com.personal_dashboard.backend.dto.request.TransactionRequest;
import com.personal_dashboard.backend.model.Subscription;
import com.personal_dashboard.backend.repository.SubscriptionRepository;
import com.personal_dashboard.backend.security.UserContext;
import com.personal_dashboard.backend.util.MoneyFlow;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;

/**
 * Recurring bills and subscriptions. A bill stores an anchor due date plus an interval
 * (e.g. every 1 MONTH, every 28 DAYs); payments are ordinary Expense transactions carrying
 * the bill's {@code subscriptionId}, so the ledger stays the single source of truth for what
 * was actually paid and the client derives "paid this cycle / due / overdue" from it.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SubscriptionService {

    public static final String DEFAULT_CATEGORY = "Subscriptions";
    public static final String DEFAULT_UNIT = "MONTH";

    /** Asia/Kolkata — pinned as the JVM default in {@code DashboardApplication}. */
    private static final ZoneId ZONE = ZoneId.systemDefault();
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE;
    /** Average days per month (365.25 / 12), for normalising non-monthly cycles. */
    private static final BigDecimal DAYS_PER_MONTH = new BigDecimal("30.4375");

    private final SubscriptionRepository subscriptionRepository;
    private final FinanceService financeService;

    public List<SubscriptionDTO> list() {
        String userId = UserContext.getRequiredUserId();
        return subscriptionRepository.findByUserId(userId).stream()
                .sorted(Comparator.comparing(s -> s.getName() == null ? "" : s.getName().toLowerCase()))
                .map(SubscriptionService::toDto)
                .toList();
    }

    public SubscriptionDTO create(SubscriptionRequest request) {
        Subscription subscription = new Subscription();
        apply(subscription, request);
        Subscription saved = subscriptionRepository.save(subscription);
        log.info("Created subscription {} '{}' (cost={}, every {} {})", saved.getId(), saved.getName(),
                saved.getCost(), saved.getIntervalCount(), saved.getIntervalUnit());
        return toDto(saved);
    }

    public SubscriptionDTO update(String id, SubscriptionRequest request) {
        Subscription subscription = findOwned(id);
        apply(subscription, request);
        Subscription saved = subscriptionRepository.save(subscription);
        log.info("Updated subscription {} '{}' (cost={}, every {} {})", id, saved.getName(),
                saved.getCost(), saved.getIntervalCount(), saved.getIntervalUnit());
        return toDto(saved);
    }

    public void delete(String id) {
        Subscription subscription = findOwned(id);
        log.info("Deleting subscription {} '{}'", id, subscription.getName());
        subscriptionRepository.delete(subscription);
    }

    /**
     * Logs one payment as an Expense linked to the bill. The row is filed under the bill's
     * own category — the old client-side "Pay" guessed "Bills & Utilities" / "Entertainment",
     * which minted a duplicate category the transaction picker didn't even offer.
     */
    public TransactionDTO pay(String id, SubscriptionPaymentRequest request) {
        Subscription subscription = findOwned(id);
        BigDecimal amount = request != null && request.getAmount() != null
                ? request.getAmount()
                : subscription.getCost();
        String date = request != null && request.getDate() != null
                ? request.getDate()
                : LocalDate.now(ZONE).format(DATE_FORMATTER);
        log.info("Recording payment of {} for subscription {} '{}' on {}", amount, id, subscription.getName(), date);
        return financeService.createTransaction(TransactionRequest.builder()
                .description(subscription.getName())
                .amount(amount)
                .category(categoryOf(subscription))
                .type(MoneyFlow.EXPENSE)
                .date(date)
                .subscriptionId(subscription.getId())
                .build());
    }

    // ─── Helpers ──────────────────────────────────────────────────────────

    private Subscription findOwned(String id) {
        String userId = UserContext.getRequiredUserId();
        return subscriptionRepository.findById(id)
                .filter(sub -> userId.equals(sub.getUserId()))
                .orElseThrow(() -> new IllegalArgumentException("Subscription not found: " + id));
    }

    private static void apply(Subscription subscription, SubscriptionRequest request) {
        subscription.setName(request.getName().trim());
        subscription.setCost(request.getCost());
        subscription.setBillingDate(parseDate(request.getBillingDate()));
        subscription.setCategory(request.getCategory() == null || request.getCategory().isBlank()
                ? DEFAULT_CATEGORY
                : request.getCategory().trim());
        subscription.setIntervalUnit(request.getIntervalUnit() == null ? DEFAULT_UNIT : request.getIntervalUnit());
        subscription.setIntervalCount(request.getIntervalCount() == null ? 1 : request.getIntervalCount());
    }

    private static Instant parseDate(String date) {
        if (date == null || date.isBlank()) {
            return null;
        }
        return LocalDate.parse(date, DATE_FORMATTER).atStartOfDay(ZONE).toInstant();
    }

    static String categoryOf(Subscription subscription) {
        return subscription.getCategory() == null || subscription.getCategory().isBlank()
                ? DEFAULT_CATEGORY
                : subscription.getCategory();
    }

    static SubscriptionDTO toDto(Subscription sub) {
        String unit = sub.getIntervalUnit() == null ? DEFAULT_UNIT : sub.getIntervalUnit();
        int count = sub.getIntervalCount() == null || sub.getIntervalCount() < 1 ? 1 : sub.getIntervalCount();
        return SubscriptionDTO.builder()
                .id(sub.getId())
                .name(sub.getName())
                .cost(sub.getCost())
                // A local date, not an Instant string: the client used to slice the UTC form,
                // which put a bill due on the 14th (IST midnight = 18:30Z on the 13th) on the 13th.
                .billingDate(sub.getBillingDate() == null
                        ? null
                        : sub.getBillingDate().atZone(ZONE).toLocalDate().format(DATE_FORMATTER))
                .category(categoryOf(sub))
                .intervalUnit(unit)
                .intervalCount(count)
                .monthlyCost(monthlyCost(sub.getCost(), unit, count))
                .build();
    }

    /** Cost normalised to one month: ₹399 every 28 days ≈ ₹434/month, ₹1,200 a year = ₹100/month. */
    static Double monthlyCost(BigDecimal cost, String unit, int count) {
        if (cost == null) {
            return 0.0;
        }
        BigDecimal perMonth = switch (unit) {
            case "DAY" -> cost.multiply(DAYS_PER_MONTH).divide(BigDecimal.valueOf(count), 2, RoundingMode.HALF_UP);
            case "WEEK" -> cost.multiply(DAYS_PER_MONTH)
                    .divide(BigDecimal.valueOf(7L * count), 2, RoundingMode.HALF_UP);
            case "YEAR" -> cost.divide(BigDecimal.valueOf(12L * count), 2, RoundingMode.HALF_UP);
            default -> cost.divide(BigDecimal.valueOf(count), 2, RoundingMode.HALF_UP);
        };
        return perMonth.doubleValue();
    }
}
