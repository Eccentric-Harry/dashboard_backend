package com.personal_dashboard.backend.service;

import com.personal_dashboard.backend.dto.FinanceAccountDTO;
import com.personal_dashboard.backend.dto.ReclassifyResultDTO;
import com.personal_dashboard.backend.dto.TransactionDTO;
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
import com.personal_dashboard.backend.util.MoneyFlow;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * All finance business logic. The single source of truth for money is the
 * {@code daily_financial_logs} collection: exactly one {@link DailyFinancialLog}
 * per user per calendar date, with transactions grouped by category (mirroring
 * how nutrition stores one {@code DailyFoodLog} per day).
 *
 * <p>There is deliberately no separate flat {@code transactions} collection — a
 * transaction only ever exists embedded inside its day's log.
 *
 * <p>What each transaction counts toward (spending, income, transfer) is decided by
 * {@link MoneyFlow} and nowhere else.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FinanceService {

    private final DailyFinancialLogRepository dailyFinancialLogRepository;
    private final FinanceAccountRepository financeAccountRepository;

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE;

    // ─── Reads ────────────────────────────────────────────────────────────

    /** Daily logs for the current user over the trailing {@code days} window. */
    public List<DailyFinancialLog> getDailyLogs(int days) {
        String userId = UserContext.getRequiredUserId();
        Instant end = Instant.now();
        Instant start = end.minus(Duration.ofDays(days));
        return dailyFinancialLogRepository.findByUserIdAndDateBetween(userId, start, end);
    }

    /**
     * Flattens the current user's logs in a date range into individual transaction
     * DTOs. Used by dashboard/reporting aggregation so finance logic stays here.
     */
    public List<TransactionDTO> getTransactionsBetween(Instant start, Instant end) {
        String userId = UserContext.getRequiredUserId();
        List<DailyFinancialLog> logs = dailyFinancialLogRepository.findByUserIdAndDateBetween(userId, start, end);
        List<TransactionDTO> out = new ArrayList<>();
        for (DailyFinancialLog dailyLog : logs) {
            dailyLog.getTransactions().forEach((category, txs) ->
                    txs.forEach(tx -> out.add(toDto(tx, category))));
        }
        out.sort(Comparator.comparing(TransactionDTO::getDate).reversed());
        return out;
    }

    /** Flattens logs in a dateString range (e.g. "2026-07-01" to "2026-07-31"). Reliable alternative to Instant-based range which can miss documents with null/mismatched date fields. */
    public List<TransactionDTO> getTransactionsByDateStringRange(String startDateString, String endDateString) {
        String userId = UserContext.getRequiredUserId();
        List<DailyFinancialLog> logs = dailyFinancialLogRepository
                .findByUserIdAndDateStringBetween(userId, startDateString, endDateString);
        List<TransactionDTO> out = new ArrayList<>();
        for (DailyFinancialLog dailyLog : logs) {
            if (dailyLog.getTransactions() == null) continue;
            dailyLog.getTransactions().forEach((category, txs) -> {
                if (txs != null) txs.forEach(tx -> out.add(toDto(tx, category)));
            });
        }
        return out;
    }

    // ─── Account / Total Balance ──────────────────────────────────────────

    /** Current running balance for the user (creates a zero account on first access). */
    public FinanceAccountDTO getAccount() {
        return toDto(getOrCreateAccount());
    }

    /** Sets the Total Balance to an absolute value ("I have ₹X right now"). */
    public FinanceAccountDTO setBalance(BigDecimal balance) {
        FinanceAccount account = getOrCreateAccount();
        log.info("Setting balance to {} for userId={} (was {})", balance, account.getUserId(), account.getBalance());
        account.setBalance(balance);
        return toDto(financeAccountRepository.save(account));
    }

    /** Returns the current user's monthly spending budget (default 20 000 if not set). */
    public BigDecimal getMonthlyBudget() {
        FinanceAccount account = getOrCreateAccount();
        return account.getMonthlyBudget() != null
                ? account.getMonthlyBudget()
                : BigDecimal.valueOf(20_000);
    }

    /** Sets the user's monthly spending budget. */
    public FinanceAccountDTO setMonthlyBudget(BigDecimal budget) {
        return updateBudget(BudgetUpdateRequest.builder().monthlyBudget(budget).build());
    }

    /**
     * Sets the monthly budget and, when supplied, what it covers (all spending, or flexible
     * spending only) and which categories count as fixed. Omitted fields are left as stored.
     */
    public FinanceAccountDTO updateBudget(BudgetUpdateRequest request) {
        FinanceAccount account = getOrCreateAccount();
        log.info("Setting monthly budget to {} (scope={}) for userId={}",
                request.getMonthlyBudget(), request.getBudgetScope(), account.getUserId());
        account.setMonthlyBudget(request.getMonthlyBudget());
        if (request.getBudgetScope() != null) {
            account.setBudgetScope(MoneyFlow.normalizeScope(request.getBudgetScope()));
        }
        if (request.getFixedCategories() != null) {
            account.setFixedCategories(request.getFixedCategories().stream()
                    .map(String::trim)
                    .filter(c -> !c.isEmpty())
                    .distinct()
                    .toList());
        }
        return toDto(financeAccountRepository.save(account));
    }

    /** The budget and what it covers, for aggregations that must match /finance's own maths. */
    public BudgetSettings getBudgetSettings() {
        FinanceAccount account = getOrCreateAccount();
        return new BudgetSettings(
                account.getMonthlyBudget() != null ? account.getMonthlyBudget() : BigDecimal.valueOf(20_000),
                MoneyFlow.normalizeScope(account.getBudgetScope()),
                effectiveFixedCategories(account));
    }

    /** Snapshot of the budget configuration. */
    public record BudgetSettings(BigDecimal monthlyBudget, String scope, List<String> fixedCategories) {
        public Set<String> fixedLower() {
            return MoneyFlow.fixedCategorySet(fixedCategories);
        }
    }

    // ─── Writes (CRUD) ────────────────────────────────────────────────────

    public TransactionDTO createTransaction(TransactionRequest request) {
        log.info("Creating {} transaction '{}' amount={} category={} date={}",
                request.getType(), request.getDescription(), request.getAmount(), request.getCategory(), request.getDate());
        Instant timestamp = combineDateWithNow(request.getDate());
        FinancialTransaction tx = FinancialTransaction.builder()
                .id(UUID.randomUUID().toString())
                .description(request.getDescription())
                .amount(request.getAmount())
                .type(request.getType())
                .direction(MoneyFlow.normalizeDirection(request.getType(), request.getDirection()))
                .subscriptionId(blankToNull(request.getSubscriptionId()))
                .timestamp(timestamp)
                .build();

        addToLog(request.getDate(), timestamp, request.getCategory(), tx);
        log.info("Created transaction {} in category '{}'", tx.getId(), request.getCategory());
        return toDto(tx, request.getCategory());
    }

    public TransactionDTO updateTransaction(String id, TransactionRequest request) {
        log.info("Updating transaction {}", id);
        // Remove the old copy wherever it currently lives, then re-insert with new values.
        DailyFinancialLog oldLog = findLogContaining(id)
                .orElseThrow(() -> new IllegalArgumentException("Transaction not found: " + id));
        FinancialTransaction oldTx = findTransactionInLog(oldLog, id)
                .orElseThrow(() -> new IllegalArgumentException("Transaction not found: " + id));
        removeFromLog(oldLog, id);

        // Preserve the originally logged time-of-day (only the date picker is editable
        // here) — carrying it onto the new date if that's what changed, so fixing an
        // amount or category never silently resets a correct time back to midnight.
        Instant timestamp = combineDateWithTime(request.getDate(), oldTx.getTimestamp());
        // The edit form never touches the bill link, so an omitted subscriptionId keeps it.
        String subscriptionId = request.getSubscriptionId() != null
                ? blankToNull(request.getSubscriptionId())
                : oldTx.getSubscriptionId();
        FinancialTransaction tx = FinancialTransaction.builder()
                .id(id) // keep the stable id across edits
                .description(request.getDescription())
                .amount(request.getAmount())
                .type(request.getType())
                .direction(MoneyFlow.normalizeDirection(request.getType(), request.getDirection()))
                .subscriptionId(subscriptionId)
                .timestamp(timestamp)
                .build();

        addToLog(request.getDate(), timestamp, request.getCategory(), tx);
        return toDto(tx, request.getCategory());
    }

    public void deleteTransaction(String id) {
        log.info("Deleting transaction {}", id);
        DailyFinancialLog dailyLog = findLogContaining(id)
                .orElseThrow(() -> new IllegalArgumentException("Transaction not found: " + id));
        removeFromLog(dailyLog, id);
    }

    /**
     * Bulk-add an imported expense (e.g. from CSV) directly onto the right day's log,
     * preserving the parsed timestamp. Runs in the current user's context.
     */
    public void addImportedExpense(String description, BigDecimal amount, String category, Instant timestamp) {
        log.info("Importing expense '{}' amount={} category={} timestamp={}", description, amount, category, timestamp);
        String dateString = timestamp.atZone(ZoneId.systemDefault()).toLocalDate().toString();
        FinancialTransaction tx = FinancialTransaction.builder()
                .id(UUID.randomUUID().toString())
                .description(description)
                .amount(amount)
                .type(MoneyFlow.EXPENSE)
                .timestamp(timestamp)
                .build();
        addToLog(dateString, timestamp, category, tx);
    }

    /**
     * Moves every transaction in one category — across all of the user's days — into another
     * category and/or a new type. This is how a legacy bucket gets fixed in one step: "To Home"
     * expenses become Transfer OUT "Family" so they stop inflating spending, or a duplicate
     * "Bills & Utilities" is merged into "Bills".
     *
     * <p>Totals are recomputed per touched day. Retyping Expense → Transfer OUT leaves the
     * balance where it was (both are money out); only a real direction flip moves it, and that
     * net delta is applied once.
     */
    public ReclassifyResultDTO reclassifyCategory(CategoryReclassifyRequest request) {
        String userId = UserContext.getRequiredUserId();
        String source = request.getCategory().trim();
        String target = blankToNull(request.getTargetCategory()) != null ? request.getTargetCategory().trim() : source;
        String newType = blankToNull(request.getType());
        log.info("Reclassifying category '{}' → '{}' (type={}, direction={}) for userId={}",
                source, target, newType, request.getDirection(), userId);

        int updated = 0;
        BigDecimal balanceDelta = BigDecimal.ZERO;
        for (DailyFinancialLog dailyLog : dailyFinancialLogRepository.findByUserId(userId)) {
            Map<String, List<FinancialTransaction>> byCategory = dailyLog.getTransactions();
            if (byCategory == null) continue;
            List<FinancialTransaction> moving = byCategory.remove(source);
            if (moving == null || moving.isEmpty()) continue;

            for (FinancialTransaction tx : moving) {
                BigDecimal before = MoneyFlow.balanceEffect(tx.getType(), tx.getDirection(), tx.getAmount());
                if (newType != null) {
                    tx.setType(newType);
                    tx.setDirection(MoneyFlow.normalizeDirection(newType, request.getDirection()));
                }
                balanceDelta = balanceDelta.add(
                        MoneyFlow.balanceEffect(tx.getType(), tx.getDirection(), tx.getAmount()).subtract(before));
                updated++;
            }
            byCategory.computeIfAbsent(target, k -> new ArrayList<>()).addAll(moving);
            recomputeTotals(dailyLog);
            dailyFinancialLogRepository.save(dailyLog);
        }

        if (balanceDelta.signum() != 0) {
            FinanceAccount account = getOrCreateAccount();
            account.setBalance(account.getBalance().add(balanceDelta));
            financeAccountRepository.save(account);
        }
        log.info("Reclassified {} transaction(s) from '{}' (balance delta {})", updated, source, balanceDelta);
        return ReclassifyResultDTO.builder()
                .updated(updated)
                .balanceDelta(balanceDelta.doubleValue())
                .build();
    }

    // ─── Internal helpers ─────────────────────────────────────────────────

    private void addToLog(String dateString, Instant timestamp, String category, FinancialTransaction tx) {
        String userId = UserContext.getRequiredUserId();
        DailyFinancialLog dailyLog = dailyFinancialLogRepository.findByUserIdAndDateString(userId, dateString)
                .orElseGet(() -> DailyFinancialLog.builder()
                        .userId(userId)
                        .dateString(dateString)
                        .date(timestamp)
                        .dailyTotals(new FinancialTotals())
                        .transactions(new LinkedHashMap<>())
                        .build());

        dailyLog.getTransactions().computeIfAbsent(category, k -> new ArrayList<>()).add(tx);
        applyToTotals(dailyLog, tx, tx.getAmount());
        dailyFinancialLogRepository.save(dailyLog);
        log.debug("Added {} transaction {} to log {}", tx.getType(), tx.getId(), dateString);

        // Money in/out also moves the running Total Balance.
        applyToBalance(tx, false);
    }

    /** Removes the transaction with {@code id} from {@code dailyLog}, adjusts totals, and persists. */
    private void removeFromLog(DailyFinancialLog dailyLog, String id) {
        boolean found = false;
        for (Map.Entry<String, List<FinancialTransaction>> entry : dailyLog.getTransactions().entrySet()) {
            Optional<FinancialTransaction> match = entry.getValue().stream()
                    .filter(t -> id.equals(t.getId())).findFirst();
            if (match.isPresent()) {
                FinancialTransaction tx = match.get();
                entry.getValue().remove(tx);
                applyToTotals(dailyLog, tx, tx.getAmount().negate());
                // Reverse the transaction's effect on the running Total Balance.
                applyToBalance(tx, true);
                found = true;
                break;
            }
        }
        if (!found) {
            log.warn("Transaction {} not found in expected log {} during removal", id, dailyLog.getId());
        }
        // Drop empty category buckets to keep the document tidy.
        dailyLog.getTransactions().values().removeIf(List::isEmpty);
        dailyFinancialLogRepository.save(dailyLog);
    }

    /** Adds {@code delta} to the day's bucket for this transaction's kind (negate to remove). */
    private void applyToTotals(DailyFinancialLog dailyLog, FinancialTransaction tx, BigDecimal delta) {
        FinancialTotals totals = dailyLog.getDailyTotals();
        if (totals == null) {
            totals = new FinancialTotals();
            dailyLog.setDailyTotals(totals);
        }
        if (MoneyFlow.isIncome(tx.getType())) {
            totals.setTotalIncome(zeroIfNull(totals.getTotalIncome()).add(delta));
        } else if (MoneyFlow.isTransfer(tx.getType())) {
            if (MoneyFlow.IN.equals(MoneyFlow.normalizeDirection(tx.getType(), tx.getDirection()))) {
                totals.setTotalTransferIn(zeroIfNull(totals.getTotalTransferIn()).add(delta));
            } else {
                totals.setTotalTransferOut(zeroIfNull(totals.getTotalTransferOut()).add(delta));
            }
        } else {
            totals.setTotalExpense(zeroIfNull(totals.getTotalExpense()).add(delta));
        }
    }

    /** Rebuilds a day's totals from its transactions — used after bulk moves. */
    private void recomputeTotals(DailyFinancialLog dailyLog) {
        dailyLog.getTransactions().values().removeIf(List::isEmpty);
        dailyLog.setDailyTotals(new FinancialTotals());
        dailyLog.getTransactions().values().forEach(txs ->
                txs.forEach(tx -> applyToTotals(dailyLog, tx, zeroIfNull(tx.getAmount()))));
    }

    /**
     * Moves the running Total Balance by one transaction: money in raises it, money out
     * (spending, transfers out) lowers it. {@code reverse} undoes it (edit/delete).
     */
    private void applyToBalance(FinancialTransaction tx, boolean reverse) {
        FinanceAccount account = getOrCreateAccount();
        BigDecimal effect = MoneyFlow.balanceEffect(tx.getType(), tx.getDirection(), tx.getAmount());
        account.setBalance(account.getBalance().add(reverse ? effect.negate() : effect));
        financeAccountRepository.save(account);
    }

    private static BigDecimal zeroIfNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static List<String> effectiveFixedCategories(FinanceAccount account) {
        return account.getFixedCategories() != null
                ? account.getFixedCategories()
                : MoneyFlow.DEFAULT_FIXED_CATEGORIES;
    }

    private FinanceAccount getOrCreateAccount() {
        String userId = UserContext.getRequiredUserId();
        return financeAccountRepository.findByUserId(userId)
                .orElseGet(() -> FinanceAccount.builder()
                        .userId(userId)
                        .balance(BigDecimal.ZERO)
                        .build());
    }

    private FinanceAccountDTO toDto(FinanceAccount account) {
        BigDecimal budget = account.getMonthlyBudget() != null
                ? account.getMonthlyBudget()
                : BigDecimal.valueOf(20_000);
        return FinanceAccountDTO.builder()
                .balance(account.getBalance().doubleValue())
                .monthlyBudget(budget.doubleValue())
                .budgetScope(MoneyFlow.normalizeScope(account.getBudgetScope()))
                .fixedCategories(effectiveFixedCategories(account))
                .build();
    }

    /** Locates the current user's log that contains a given transaction id (one doc per day). */
    private Optional<DailyFinancialLog> findLogContaining(String id) {
        String userId = UserContext.getRequiredUserId();
        return dailyFinancialLogRepository.findByUserId(userId).stream()
                .filter(log -> log.getTransactions().values().stream()
                        .flatMap(List::stream)
                        .anyMatch(t -> id.equals(t.getId())))
                .findFirst();
    }

    /** Finds a transaction with {@code id} within an already-located day's log. */
    private Optional<FinancialTransaction> findTransactionInLog(DailyFinancialLog dailyLog, String id) {
        return dailyLog.getTransactions().values().stream()
                .flatMap(List::stream)
                .filter(t -> id.equals(t.getId()))
                .findFirst();
    }

    /**
     * The add-transaction form only has a date picker, no time input, so a new
     * transaction is stamped with the current wall-clock time rather than midnight —
     * "now" is the closest available approximation of when the money actually moved,
     * including for a backdated entry (its time becomes when it was logged).
     */
    private Instant combineDateWithNow(String dateString) {
        ZoneId zone = ZoneId.systemDefault();
        return LocalDate.parse(dateString, DATE_FORMATTER)
                .atTime(LocalTime.now(zone))
                .atZone(zone)
                .toInstant();
    }

    /** Re-dates {@code originalTimestamp} onto {@code dateString} while keeping its time-of-day. */
    private Instant combineDateWithTime(String dateString, Instant originalTimestamp) {
        ZoneId zone = ZoneId.systemDefault();
        LocalTime time = originalTimestamp.atZone(zone).toLocalTime();
        return LocalDate.parse(dateString, DATE_FORMATTER)
                .atTime(time)
                .atZone(zone)
                .toInstant();
    }

    private TransactionDTO toDto(FinancialTransaction tx, String category) {
        return TransactionDTO.builder()
                .id(tx.getId())
                .description(tx.getDescription())
                .amount(tx.getAmount() != null ? tx.getAmount().doubleValue() : 0.0)
                .category(category)
                .type(tx.getType())
                .direction(tx.getDirection())
                .subscriptionId(tx.getSubscriptionId())
                .date(tx.getTimestamp() != null ? tx.getTimestamp().toString() : Instant.EPOCH.toString())
                .build();
    }
}
