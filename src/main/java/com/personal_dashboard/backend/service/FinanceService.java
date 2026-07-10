package com.personal_dashboard.backend.service;

import com.personal_dashboard.backend.dto.FinanceAccountDTO;
import com.personal_dashboard.backend.dto.TransactionDTO;
import com.personal_dashboard.backend.dto.request.TransactionRequest;
import com.personal_dashboard.backend.model.DailyFinancialLog;
import com.personal_dashboard.backend.model.FinanceAccount;
import com.personal_dashboard.backend.model.FinancialTotals;
import com.personal_dashboard.backend.model.FinancialTransaction;
import com.personal_dashboard.backend.repository.DailyFinancialLogRepository;
import com.personal_dashboard.backend.repository.FinanceAccountRepository;
import com.personal_dashboard.backend.security.UserContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * All finance business logic. The single source of truth for money is the
 * {@code daily_financial_logs} collection: exactly one {@link DailyFinancialLog}
 * per user per calendar date, with transactions grouped by category (mirroring
 * how nutrition stores one {@code DailyFoodLog} per day).
 *
 * <p>There is deliberately no separate flat {@code transactions} collection — a
 * transaction only ever exists embedded inside its day's log.
 */
@Service
@RequiredArgsConstructor
public class FinanceService {

    private final DailyFinancialLogRepository dailyFinancialLogRepository;
    private final FinanceAccountRepository financeAccountRepository;

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final String INCOME = "Income";

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
        for (DailyFinancialLog log : logs) {
            log.getTransactions().forEach((category, txs) ->
                    txs.forEach(tx -> out.add(toDto(tx, category))));
        }
        out.sort(Comparator.comparing(TransactionDTO::getDate).reversed());
        return out;
    }

    /** Flattens logs in a dateString range (e.g. "2026-07-01" to "2026-07-31"). Reliable alternative to Instant-based range which can miss documents with null/mismatched date fields. */
    public List<TransactionDTO> getTransactionsByDateStringRange(String startDateString, String endDateString) {
        String userId = UserContext.getRequiredUserId();
        List<DailyFinancialLog> logs = dailyFinancialLogRepository
                .findByUserIdAndDateStringGreaterThanEqualAndDateStringLessThanEqual(userId, startDateString, endDateString);
        List<TransactionDTO> out = new ArrayList<>();
        for (DailyFinancialLog log : logs) {
            log.getTransactions().forEach((category, txs) ->
                    txs.forEach(tx -> out.add(toDto(tx, category))));
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
        FinanceAccount account = getOrCreateAccount();
        account.setMonthlyBudget(budget);
        return toDto(financeAccountRepository.save(account));
    }

    // ─── Writes (CRUD) ────────────────────────────────────────────────────

    public TransactionDTO createTransaction(TransactionRequest request) {
        Instant timestamp = toInstant(request.getDate());
        FinancialTransaction tx = FinancialTransaction.builder()
                .id(UUID.randomUUID().toString())
                .description(request.getDescription())
                .amount(request.getAmount())
                .type(request.getType())
                .timestamp(timestamp)
                .build();

        addToLog(request.getDate(), timestamp, request.getCategory(), tx);
        return toDto(tx, request.getCategory());
    }

    public TransactionDTO updateTransaction(String id, TransactionRequest request) {
        // Remove the old copy wherever it currently lives, then re-insert with new values.
        DailyFinancialLog oldLog = findLogContaining(id)
                .orElseThrow(() -> new IllegalArgumentException("Transaction not found: " + id));
        removeFromLog(oldLog, id);

        Instant timestamp = toInstant(request.getDate());
        FinancialTransaction tx = FinancialTransaction.builder()
                .id(id) // keep the stable id across edits
                .description(request.getDescription())
                .amount(request.getAmount())
                .type(request.getType())
                .timestamp(timestamp)
                .build();

        addToLog(request.getDate(), timestamp, request.getCategory(), tx);
        return toDto(tx, request.getCategory());
    }

    public void deleteTransaction(String id) {
        DailyFinancialLog log = findLogContaining(id)
                .orElseThrow(() -> new IllegalArgumentException("Transaction not found: " + id));
        removeFromLog(log, id);
    }

    /**
     * Bulk-add an imported expense (e.g. from CSV) directly onto the right day's log,
     * preserving the parsed timestamp. Runs in the current user's context.
     */
    public void addImportedExpense(String description, BigDecimal amount, String category, Instant timestamp) {
        String dateString = timestamp.atZone(ZoneId.systemDefault()).toLocalDate().toString();
        FinancialTransaction tx = FinancialTransaction.builder()
                .id(UUID.randomUUID().toString())
                .description(description)
                .amount(amount)
                .type("Expense")
                .timestamp(timestamp)
                .build();
        addToLog(dateString, timestamp, category, tx);
    }

    // ─── Internal helpers ─────────────────────────────────────────────────

    private void addToLog(String dateString, Instant timestamp, String category, FinancialTransaction tx) {
        String userId = UserContext.getRequiredUserId();
        DailyFinancialLog log = dailyFinancialLogRepository.findByUserIdAndDateString(userId, dateString)
                .orElseGet(() -> DailyFinancialLog.builder()
                        .userId(userId)
                        .dateString(dateString)
                        .date(timestamp)
                        .dailyTotals(new FinancialTotals())
                        .transactions(new LinkedHashMap<>())
                        .build());

        log.getTransactions().computeIfAbsent(category, k -> new ArrayList<>()).add(tx);
        applyToTotals(log, tx.getType(), tx.getAmount());
        dailyFinancialLogRepository.save(log);

        // Money in/out also moves the running Total Balance.
        applyToBalance(tx.getType(), tx.getAmount());
    }

    /** Removes the transaction with {@code id} from {@code log}, adjusts totals, and persists. */
    private void removeFromLog(DailyFinancialLog log, String id) {
        for (Map.Entry<String, List<FinancialTransaction>> entry : log.getTransactions().entrySet()) {
            Optional<FinancialTransaction> match = entry.getValue().stream()
                    .filter(t -> id.equals(t.getId())).findFirst();
            if (match.isPresent()) {
                FinancialTransaction tx = match.get();
                entry.getValue().remove(tx);
                applyToTotals(log, tx.getType(), tx.getAmount().negate());
                // Reverse the transaction's effect on the running Total Balance.
                applyToBalance(tx.getType(), tx.getAmount().negate());
                break;
            }
        }
        // Drop empty category buckets to keep the document tidy.
        log.getTransactions().values().removeIf(List::isEmpty);
        dailyFinancialLogRepository.save(log);
    }

    private void applyToTotals(DailyFinancialLog log, String type, BigDecimal delta) {
        FinancialTotals totals = log.getDailyTotals();
        if (INCOME.equalsIgnoreCase(type)) {
            totals.setTotalIncome(totals.getTotalIncome().add(delta));
        } else {
            totals.setTotalExpense(totals.getTotalExpense().add(delta));
        }
    }

    /**
     * Moves the running Total Balance by a transaction of the given {@code amount}:
     * income increases the balance, expense decreases it. Pass a negated amount to
     * reverse a transaction's effect (edit/delete).
     */
    private void applyToBalance(String type, BigDecimal amount) {
        FinanceAccount account = getOrCreateAccount();
        BigDecimal signed = INCOME.equalsIgnoreCase(type) ? amount : amount.negate();
        account.setBalance(account.getBalance().add(signed));
        financeAccountRepository.save(account);
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

    private Instant toInstant(String dateString) {
        return LocalDate.parse(dateString, DATE_FORMATTER)
                .atStartOfDay(ZoneId.systemDefault())
                .toInstant();
    }

    private TransactionDTO toDto(FinancialTransaction tx, String category) {
        return TransactionDTO.builder()
                .id(tx.getId())
                .description(tx.getDescription())
                .amount(tx.getAmount().doubleValue())
                .category(category)
                .type(tx.getType())
                .date(tx.getTimestamp().toString())
                .build();
    }
}
