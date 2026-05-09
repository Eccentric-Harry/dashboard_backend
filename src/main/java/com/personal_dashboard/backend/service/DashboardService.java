package com.personal_dashboard.backend.service;

import com.personal_dashboard.backend.dto.*;
import com.personal_dashboard.backend.model.*;
import com.personal_dashboard.backend.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class DashboardService {

    private final DailyFoodLogService dailyFoodLogService;
    private final DailyHealthRecordRepository dailyHealthRecordRepository;
    private final TransactionRepository transactionRepository;
    private final DailyLogRepository dailyLogRepository;

    private static final int CALORIE_GOAL = 2000;
    private static final int PROTEIN_GOAL = 100;
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE;

    /**
     * Aggregates data for the dashboard for a given target date.
     */
    public HealthMetrics aggregateHealthData(LocalDate targetDate) {
        // Fetch today's daily food log
        String dateStr = targetDate.format(DATE_FORMATTER);
        DailyFoodLog dailyLog = dailyFoodLogService.getDailyLog(dateStr);

        // Get daily totals directly from the daily log
        int totalCalories = dailyLog.getDailyTotals() != null ? dailyLog.getDailyTotals().getTotalCalories() : 0;
        int totalProtein = dailyLog.getDailyTotals() != null ? dailyLog.getDailyTotals().getTotalProteinGrams() : 0;

        // Fetch health record for the day
        Optional<DailyHealthRecord> healthRecord = dailyHealthRecordRepository.findByDate(targetDate);

        // Build DailyFoodIntake
        DailyFoodIntake dailyFood = DailyFoodIntake.builder()
                .date(targetDate.format(DATE_FORMATTER))
                .calories(totalCalories)
                .calorieGoal(CALORIE_GOAL)
                .proteinGrams(totalProtein)
                .proteinGoalGrams(PROTEIN_GOAL)
                .build();

        // Build circular goals
        List<CircularProgressMetric> circularGoals = buildCircularGoals(totalCalories, totalProtein);

        // Build sleep hours list
        List<SleepEntry> sleepHours = healthRecord
                .map(record -> SleepEntry.builder()
                        .date(targetDate.format(DATE_FORMATTER))
                        .hours(record.getSleepHours())
                        .build())
                .map(Collections::singletonList)
                .orElse(Collections.emptyList());

        // Build weight trend list
        List<WeightEntry> weightTrend = healthRecord
                .map(record -> WeightEntry.builder()
                        .date(targetDate.format(DATE_FORMATTER))
                        .weightKg(record.getWeightKg())
                        .build())
                .map(Collections::singletonList)
                .orElse(Collections.emptyList());

        // Flatten daily log meals into FoodEntryDTOs for backward compatibility
        List<FoodEntryDTO> foodEntryDTOs = dailyFoodLogService.flattenToFoodEntryDTOs(dailyLog);

        return HealthMetrics.builder()
                .dailyFood(dailyFood)
                .circularGoals(circularGoals)
                .sleepHours(sleepHours)
                .weightTrend(weightTrend)
                .foodEntries(foodEntryDTOs)
                .hydration(dailyFoodLogService.toHydrationDto(dateStr, dailyLog.getHydration()))
                .build();
    }

    /**
     * Aggregates financial data for the current month.
     */
    public FinanceMetrics aggregateFinanceData(LocalDate targetDate) {
        YearMonth currentMonth = YearMonth.from(targetDate);
        LocalDate monthStart = currentMonth.atDay(1);
        LocalDate monthEnd = currentMonth.atEndOfMonth();

        // Fetch all transactions for the month
        List<Transaction> transactions = transactionRepository.findByDateBetween(
                monthStart.atStartOfDay(ZoneId.systemDefault()).toInstant(),
                monthEnd.atTime(23, 59, 59).atZone(ZoneId.systemDefault()).toInstant()
        );

        // Separate expenses and income
        BigDecimal totalExpenses = transactions.stream()
                .filter(t -> "Expense".equals(t.getType()))
                .map(Transaction::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal totalIncome = transactions.stream()
                .filter(t -> "Income".equals(t.getType()))
                .map(Transaction::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // Mock budget
        BigDecimal totalBudget = BigDecimal.valueOf(5000.0);

        // Calculate savings rate
        double savingsRate = totalIncome.compareTo(BigDecimal.ZERO) > 0
                ? totalIncome.subtract(totalExpenses).divide(totalIncome, 2, java.math.RoundingMode.HALF_UP).doubleValue() * 100
                : 0.0;

        // Build budget items (grouped by category)
        List<BudgetItem> budgetItems = buildBudgetItems(transactions);

        // Map transactions to DTOs
        List<TransactionDTO> transactionDTOs = transactions.stream()
                .map(t -> TransactionDTO.builder()
                        .id(t.getId())
                        .description(t.getDescription())
                        .amount(t.getAmount().doubleValue())
                        .category(t.getCategory())
                        .type(t.getType())
                        .date(t.getDate().toString())
                        .build())
                .collect(Collectors.toList());

        return FinanceMetrics.builder()
                .month(currentMonth.format(DateTimeFormatter.ofPattern("yyyy-MM")))
                .totalBudget(totalBudget.doubleValue())
                .totalSpent(totalExpenses.doubleValue())
                .savingsRatePercent(savingsRate)
                .budgetItems(budgetItems)
                .transactions(transactionDTOs)
                .build();
    }

    /**
     * Aggregates coding activity for the last 7 days.
     */
    public CodingMetrics aggregateCodingData(LocalDate targetDate) {
        LocalDate sevenDaysAgo = targetDate.minusDays(6); // Include target date = 7 days total

        List<DailyLog> dailyLogs = dailyLogRepository.findByDateBetween(sevenDaysAgo, targetDate);

        // Build learning heatmap
        List<LearningHeatmapEntry> heatmapEntries = dailyLogs.stream()
                .map(log -> {
                    // Calculate intensity (0-4) based on learning count and commits
                    int intensity = calculateIntensity(
                            log.getNewLearnings() != null ? log.getNewLearnings().size() : 0,
                            log.getGithubCommits() != null ? log.getGithubCommits() : 0
                    );
                    String topic = log.getNewLearnings() != null && !log.getNewLearnings().isEmpty()
                            ? log.getNewLearnings().get(0)
                            : "General";

                    return LearningHeatmapEntry.builder()
                            .date(log.getDate().format(DATE_FORMATTER))
                            .intensity(intensity)
                            .topic(topic)
                            .build();
                })
                .collect(Collectors.toList());

        // Build coding stats
        int totalCommits = dailyLogs.stream()
                .mapToInt(log -> log.getGithubCommits() != null ? log.getGithubCommits() : 0)
                .sum();

        int totalLeetCode = dailyLogs.stream()
                .mapToInt(log -> log.getLeetCodeSolved() != null ? log.getLeetCodeSolved() : 0)
                .sum();

        CodingStats stats = CodingStats.builder()
                .focusedHours(calculateFocusedHours(dailyLogs))
                .deepWorkSessions(calculateDeepWorkSessions(dailyLogs))
                .weeklyLearningCount(calculateWeeklyLearning(dailyLogs))
                .streakDays(calculateStreak(dailyLogs))
                .build();

        // Mock platform metrics
        PlatformMetricPlaceholder github = PlatformMetricPlaceholder.builder()
                .solved(totalCommits)
                .weeklyDelta(0)
                .target(50)
                .build();

        PlatformMetricPlaceholder leetCode = PlatformMetricPlaceholder.builder()
                .solved(totalLeetCode)
                .weeklyDelta(0)
                .target(10)
                .build();

        return CodingMetrics.builder()
                .learningHeatmap(heatmapEntries)
                .stats(stats)
                .github(github)
                .leetCode(leetCode)
                .build();
    }

    /**
     * Helper: Build circular progress metrics for health
     */
    private List<CircularProgressMetric> buildCircularGoals(int calories, int protein) {
        List<CircularProgressMetric> goals = new ArrayList<>();

        goals.add(CircularProgressMetric.builder()
                .label("Calories")
                .value(calories)
                .target(CALORIE_GOAL)
                .unit("kcal")
                .progressPercent((double) calories / CALORIE_GOAL * 100)
                .build());

        goals.add(CircularProgressMetric.builder()
                .label("Protein")
                .value(protein)
                .target(PROTEIN_GOAL)
                .unit("g")
                .progressPercent((double) protein / PROTEIN_GOAL * 100)
                .build());

        return goals;
    }

    /**
     * Helper: Build budget items grouped by category
     */
    private List<BudgetItem> buildBudgetItems(List<Transaction> transactions) {
        Map<String, BigDecimal> categorySpend = new HashMap<>();

        transactions.stream()
                .filter(t -> "Expense".equals(t.getType()))
                .forEach(t -> categorySpend.merge(t.getCategory(), t.getAmount(), BigDecimal::add));

        // Mock per-category budgets
        Map<String, BigDecimal> categoryBudgets = Map.of(
                "Shopping", BigDecimal.valueOf(400),
                "Food & Drink", BigDecimal.valueOf(500),
                "Groceries", BigDecimal.valueOf(800),
                "Subscriptions", BigDecimal.valueOf(200),
                "General", BigDecimal.valueOf(1000)
        );

        return categorySpend.entrySet().stream()
                .map(entry -> {
                    String category = entry.getKey();
                    BigDecimal spent = entry.getValue();
                    BigDecimal budget = categoryBudgets.getOrDefault(category, BigDecimal.valueOf(1000));
                    BigDecimal remaining = budget.subtract(spent);
                    double utilization = spent.divide(budget, 2, java.math.RoundingMode.HALF_UP).doubleValue() * 100;

                    String status = utilization > 90 ? "danger" : utilization > 70 ? "warning" : "safe";

                    return BudgetItem.builder()
                            .category(category)
                            .budget(budget.doubleValue())
                            .spent(spent.doubleValue())
                            .remaining(remaining.doubleValue())
                            .utilizationPercent(utilization)
                            .status(status)
                            .build();
                })
                .collect(Collectors.toList());
    }

    /**
     * Helper: Calculate intensity (0-4) based on activity
     */
    private int calculateIntensity(int learnings, int commits) {
        int total = learnings + commits;
        if (total >= 10) return 4;
        if (total >= 7) return 3;
        if (total >= 4) return 2;
        if (total >= 1) return 1;
        return 0;
    }

    /**
     * Helper: Calculate focused hours (mock implementation)
     */
    private double calculateFocusedHours(List<DailyLog> logs) {
        return logs.size() * 2.5; // Mock: 2.5 hours per day
    }

    /**
     * Helper: Calculate deep work sessions
     */
    private int calculateDeepWorkSessions(List<DailyLog> logs) {
        return (int) logs.stream()
                .filter(log -> log.getDailyOneThingCompleted() != null && log.getDailyOneThingCompleted())
                .count();
    }

    /**
     * Helper: Calculate weekly learning count
     */
    private int calculateWeeklyLearning(List<DailyLog> logs) {
        return logs.stream()
                .mapToInt(log -> log.getNewLearnings() != null ? log.getNewLearnings().size() : 0)
                .sum();
    }

    /**
     * Helper: Calculate streak (consecutive days with activity)
     */
    private int calculateStreak(List<DailyLog> logs) {
        if (logs.isEmpty()) return 0;

        int streak = 0;
        for (int i = 0; i < logs.size(); i++) {
            DailyLog log = logs.get(i);
            if (log.getGithubCommits() != null && log.getGithubCommits() > 0) {
                streak++;
            } else {
                streak = 0;
            }
        }
        return streak;
    }

    /**
     * Main method: Get complete dashboard data for a target date
     */
    public ApiResponse<Map<String, Object>> getDashboardData(LocalDate targetDate) {
        HealthMetrics health = aggregateHealthData(targetDate);
        FinanceMetrics finance = aggregateFinanceData(targetDate);
        CodingMetrics coding = aggregateCodingData(targetDate);

        Map<String, Object> dashboardData = new LinkedHashMap<>();
        dashboardData.put("health", health);
        dashboardData.put("finance", finance);
        dashboardData.put("coding", coding);
        dashboardData.put("date", targetDate.format(DATE_FORMATTER));

        ApiMeta meta = ApiMeta.builder()
                .requestId(UUID.randomUUID().toString())
                .timestamp(Instant.now().toString())
                .source("api")
                .build();

        return ApiResponse.<Map<String, Object>>builder()
                .data(dashboardData)
                .meta(meta)
                .build();
    }

    /**
     * Get nutrition summary for a specific date (with 7-day trend)
     */
    public Map<String, Object> getNutritionSummary(LocalDate targetDate) {
        LocalDate sevenDaysAgo = targetDate.minusDays(6);

        List<DailyFoodLog> dailyLogs = dailyFoodLogService.getDailyLogsForRange(sevenDaysAgo, targetDate);

        // Build daily totals maps
        Map<String, Integer> dailyCalories = new LinkedHashMap<>();
        Map<String, Integer> dailyProtein = new LinkedHashMap<>();

        // Initialize all days with 0
        for (LocalDate date = sevenDaysAgo; !date.isAfter(targetDate); date = date.plusDays(1)) {
            dailyCalories.put(date.format(DATE_FORMATTER), 0);
            dailyProtein.put(date.format(DATE_FORMATTER), 0);
        }

        // Fill in actual data from daily logs
        for (DailyFoodLog log : dailyLogs) {
            String dateKey = log.getDate().format(DATE_FORMATTER);
            if (log.getDailyTotals() != null) {
                dailyCalories.put(dateKey, log.getDailyTotals().getTotalCalories());
                dailyProtein.put(dateKey, log.getDailyTotals().getTotalProteinGrams());
            }
        }

        // Group by meal type for today
        String todayKey = targetDate.format(DATE_FORMATTER);
        Map<String, Integer> mealTypeBreakdown = new LinkedHashMap<>();
        DailyFoodLog todayLog = dailyLogs.stream()
                .filter(log -> todayKey.equals(log.getId()))
                .findFirst()
                .orElse(null);

        if (todayLog != null && todayLog.getMeals() != null) {
            for (Map.Entry<String, java.util.List<MealEntry>> mealGroup : todayLog.getMeals().entrySet()) {
                int mealCalories = mealGroup.getValue().stream()
                        .mapToInt(e -> e.getCalories() != null ? e.getCalories() : 0)
                        .sum();
                mealTypeBreakdown.put(mealGroup.getKey(), mealCalories);
            }
        }

        // Build response
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("date", targetDate.format(DATE_FORMATTER));
        response.put("dailyCalories", dailyCalories);
        response.put("dailyProtein", dailyProtein);
        response.put("mealTypeBreakdown", mealTypeBreakdown);
        response.put("todayTotalCalories", dailyCalories.getOrDefault(todayKey, 0));
        response.put("todayTotalProtein", dailyProtein.getOrDefault(todayKey, 0));
        response.put("calorieGoal", CALORIE_GOAL);
        response.put("proteinGoal", PROTEIN_GOAL);

        return response;
    }

    /**
     * Get spending summary for a specific month
     */
    public Map<String, Object> getSpendingSummary(YearMonth month) {
        LocalDate monthStart = month.atDay(1);
        LocalDate monthEnd = month.atEndOfMonth();

        List<Transaction> transactions = transactionRepository.findByDateBetween(
                monthStart.atStartOfDay(ZoneId.systemDefault()).toInstant(),
                monthEnd.atTime(23, 59, 59).atZone(ZoneId.systemDefault()).toInstant()
        );

        // Calculate total spent
        BigDecimal totalExpenses = transactions.stream()
                .filter(t -> "Expense".equals(t.getType()))
                .map(Transaction::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // Group by category
        Map<String, BigDecimal> categorySpend = transactions.stream()
                .filter(t -> "Expense".equals(t.getType()))
                .collect(Collectors.groupingBy(
                        Transaction::getCategory,
                        Collectors.reducing(BigDecimal.ZERO, Transaction::getAmount, BigDecimal::add)
                ));

        // Build response
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("month", month.format(DateTimeFormatter.ofPattern("yyyy-MM")));
        response.put("totalSpent", totalExpenses.doubleValue());
        response.put("monthlyBudget", 20000.0);
        response.put("budgetRemaining", 20000.0 - totalExpenses.doubleValue());
        response.put("budgetUtilization", totalExpenses.doubleValue() / 20000.0 * 100);
        response.put("categoryBreakdown", categorySpend.entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        e -> e.getValue().doubleValue()
                )));
        response.put("transactionCount", transactions.size());

        return response;
    }
}
