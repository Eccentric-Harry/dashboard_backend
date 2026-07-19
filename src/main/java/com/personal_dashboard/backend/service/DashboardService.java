package com.personal_dashboard.backend.service;

import com.personal_dashboard.backend.dto.*;
import com.personal_dashboard.backend.model.*;
import com.personal_dashboard.backend.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class DashboardService {

        private final DailyFoodLogService dailyFoodLogService;
        private final DailyHealthRecordRepository dailyHealthRecordRepository;
        private final FinanceService financeService;
        private final DailyLogRepository dailyLogRepository;
        private final LearningRepository learningRepository;
        private final DailyTaskRepository dailyTaskRepository;
        private final LearningPursuitRepository learningPursuitRepository;
        private final com.personal_dashboard.backend.repository.UserAccountRepository userAccountRepository;

        private static final int CALORIE_GOAL = 2000;
        private static final int PROTEIN_GOAL = 100;
        private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE;

        /**
         * Aggregates data for the dashboard for a given target date.
         */
        public HealthMetrics aggregateHealthData(LocalDate targetDate) {
                log.debug("Aggregating health dashboard data for {}", targetDate);
                // Fetch today's daily food log
                String dateStr = targetDate.format(DATE_FORMATTER);
                DailyFoodLog dailyLog = dailyFoodLogService.getDailyLog(dateStr);

                // Get daily totals directly from the daily log
                int totalCalories = dailyLog.getDailyTotals() != null ? dailyLog.getDailyTotals().getTotalCalories()
                                : 0;
                int totalProtein = dailyLog.getDailyTotals() != null ? dailyLog.getDailyTotals().getTotalProteinGrams()
                                : 0;

                // Fetch health record for the day
                String userId = com.personal_dashboard.backend.security.UserContext.getRequiredUserId();
                Optional<DailyHealthRecord> healthRecord = dailyHealthRecordRepository.findByUserIdAndDate(userId, targetDate);

                // Build DailyFoodIntake
                int calorieGoal = dailyLog.getCalorieGoal() != null ? dailyLog.getCalorieGoal() : CALORIE_GOAL;
                int proteinGoal = dailyLog.getProteinGoal() != null ? dailyLog.getProteinGoal() : PROTEIN_GOAL;

                DailyFoodIntake dailyFood = DailyFoodIntake.builder()
                                .date(targetDate.format(DATE_FORMATTER))
                                .calories(totalCalories)
                                .calorieGoal(calorieGoal)
                                .proteinGrams(totalProtein)
                                .proteinGoalGrams(proteinGoal)
                                .build();

                // Build circular goals — include Carbs and Fat when user profile has DynamicTargets
                DailyTotals totals = dailyLog.getDailyTotals();
                int totalCarbs = totals != null && totals.getTotalCarbsGrams() != null ? totals.getTotalCarbsGrams() : 0;
                int totalFat = totals != null && totals.getTotalFatGrams() != null ? totals.getTotalFatGrams() : 0;

                // Try to load user profile for dynamic macro targets
                com.personal_dashboard.backend.model.UserAccount userProfile =
                        userAccountRepository.findById(userId).orElse(null);
                int carbsGoal = 0;
                int fatGoal = 0;
                if (userProfile != null && userProfile.getDynamicTargets() != null) {
                    com.personal_dashboard.backend.model.UserAccount.DynamicTargets dt = userProfile.getDynamicTargets();
                    if (dt.getCalculatedCarbs() != null) carbsGoal = dt.getCalculatedCarbs();
                    if (dt.getCalculatedFat() != null) fatGoal = dt.getCalculatedFat();
                }

                List<CircularProgressMetric> circularGoals = buildCircularGoals(
                        totalCalories, totalProtein, calorieGoal, proteinGoal,
                        totalCarbs, carbsGoal, totalFat, fatGoal);

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
                log.debug("Aggregating finance dashboard data for month of {}", targetDate);
                YearMonth currentMonth = YearMonth.from(targetDate);
                LocalDate monthStart = currentMonth.atDay(1);
                LocalDate monthEnd = currentMonth.atEndOfMonth();

                // Fetch all transactions for the month (flattened from the daily logs)
                List<TransactionDTO> transactions = financeService.getTransactionsByDateStringRange(
                                monthStart.format(DateTimeFormatter.ISO_LOCAL_DATE),
                                monthEnd.format(DateTimeFormatter.ISO_LOCAL_DATE));

                // Separate expenses and income
                BigDecimal totalExpenses = transactions.stream()
                                .filter(t -> "Expense".equals(t.getType()))
                                .map(t -> BigDecimal.valueOf(t.getAmount()))
                                .reduce(BigDecimal.ZERO, BigDecimal::add);

                BigDecimal totalIncome = transactions.stream()
                                .filter(t -> "Income".equals(t.getType()))
                                .map(t -> BigDecimal.valueOf(t.getAmount()))
                                .reduce(BigDecimal.ZERO, BigDecimal::add);

                // Mock budget
                BigDecimal totalBudget = BigDecimal.valueOf(5000.0);

                // Calculate savings rate
                double savingsRate = totalIncome.compareTo(BigDecimal.ZERO) > 0
                                ? totalIncome.subtract(totalExpenses)
                                                .divide(totalIncome, 2, java.math.RoundingMode.HALF_UP).doubleValue()
                                                * 100
                                : 0.0;

                // Build budget items (grouped by category)
                List<BudgetItem> budgetItems = buildBudgetItems(transactions);

                return FinanceMetrics.builder()
                                .month(currentMonth.format(DateTimeFormatter.ofPattern("yyyy-MM")))
                                .totalBudget(totalBudget.doubleValue())
                                .totalSpent(totalExpenses.doubleValue())
                                .savingsRatePercent(savingsRate)
                                .budgetItems(budgetItems)
                                .transactions(transactions)
                                .build();
        }

        /**
         * Aggregates coding activity for the last 7 days.
         */
        public CodingMetrics aggregateCodingData(LocalDate targetDate) {
                log.debug("Aggregating coding/learning heatmap for the 7 days ending {}", targetDate);
                LocalDate sevenDaysAgo = targetDate.minusDays(6); // Include target date = 7 days total

                String userId = com.personal_dashboard.backend.security.UserContext.getRequiredUserId();
                List<DailyLog> dailyLogs = dailyLogRepository.findByUserIdAndDateRange(userId, sevenDaysAgo, targetDate);

                // Build learning heatmap
                List<LearningHeatmapEntry> heatmapEntries = dailyLogs.stream()
                                .map(dailyLog -> {
                                        // Calculate intensity (0-4) based on learning count
                                        int intensity = calculateIntensity(
                                                        dailyLog.getNewLearnings() != null ? dailyLog.getNewLearnings().size()
                                                                        : 0);
                                        String topic = dailyLog.getNewLearnings() != null && !dailyLog.getNewLearnings().isEmpty()
                                                        ? dailyLog.getNewLearnings().get(0)
                                                        : "General";

                                        return LearningHeatmapEntry.builder()
                                                        .date(dailyLog.getDate().format(DATE_FORMATTER))
                                                        .intensity(intensity)
                                                        .topic(topic)
                                                        .build();
                                })
                                .collect(Collectors.toList());

                return CodingMetrics.builder()
                                .learningHeatmap(heatmapEntries)
                                .build();
        }

        /**
         * Helper: Build circular progress metrics for health.
         * Includes Calories, Protein, Carbs, and Fat rings.
         * Carbs and Fat are only shown when targets > 0 (i.e., user has set DynamicTargets).
         */
        private List<CircularProgressMetric> buildCircularGoals(
                int calories, int protein, int calorieGoal, int proteinGoal,
                int carbs, int carbsGoal, int fat, int fatGoal) {
                List<CircularProgressMetric> goals = new ArrayList<>();

                goals.add(CircularProgressMetric.builder()
                                .label("Calories")
                                .value(calories)
                                .target(calorieGoal)
                                .unit("kcal")
                                .progressPercent(calorieGoal > 0 ? (double) calories / calorieGoal * 100 : 0)
                                .build());

                goals.add(CircularProgressMetric.builder()
                                .label("Protein")
                                .value(protein)
                                .target(proteinGoal)
                                .unit("g")
                                .progressPercent(proteinGoal > 0 ? (double) protein / proteinGoal * 100 : 0)
                                .build());

                if (carbsGoal > 0) {
                        goals.add(CircularProgressMetric.builder()
                                        .label("Carbs")
                                        .value(carbs)
                                        .target(carbsGoal)
                                        .unit("g")
                                        .progressPercent((double) carbs / carbsGoal * 100)
                                        .build());
                }

                if (fatGoal > 0) {
                        goals.add(CircularProgressMetric.builder()
                                        .label("Fat")
                                        .value(fat)
                                        .target(fatGoal)
                                        .unit("g")
                                        .progressPercent((double) fat / fatGoal * 100)
                                        .build());
                }

                return goals;
        }

        /**
         * Helper: Build budget items grouped by category
         */
        private List<BudgetItem> buildBudgetItems(List<TransactionDTO> transactions) {
                Map<String, BigDecimal> categorySpend = new HashMap<>();

                transactions.stream()
                                .filter(t -> "Expense".equals(t.getType()))
                                .forEach(t -> categorySpend.merge(t.getCategory(),
                                                BigDecimal.valueOf(t.getAmount()), BigDecimal::add));

                // Mock per-category budgets
                Map<String, BigDecimal> categoryBudgets = Map.of(
                                "Shopping", BigDecimal.valueOf(400),
                                "Food & Drink", BigDecimal.valueOf(500),
                                "Groceries", BigDecimal.valueOf(800),
                                "Subscriptions", BigDecimal.valueOf(200),
                                "General", BigDecimal.valueOf(1000));

                return categorySpend.entrySet().stream()
                                .map(entry -> {
                                        String category = entry.getKey();
                                        BigDecimal spent = entry.getValue();
                                        BigDecimal budget = categoryBudgets.getOrDefault(category,
                                                        BigDecimal.valueOf(1000));
                                        BigDecimal remaining = budget.subtract(spent);
                                        double utilization = spent.divide(budget, 2, java.math.RoundingMode.HALF_UP)
                                                        .doubleValue() * 100;

                                        String status = utilization > 90 ? "danger"
                                                        : utilization > 70 ? "warning" : "safe";

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

        private int calculateIntensity(int activityCount) {
                if (activityCount >= 10)
                        return 4;
                if (activityCount >= 7)
                        return 3;
                if (activityCount >= 4)
                        return 2;
                if (activityCount >= 1)
                        return 1;
                return 0;
        }

        /**
         * Main method: Get complete dashboard data for a target date
         */
        public ApiResponse<Map<String, Object>> getDashboardData(LocalDate targetDate) {
                log.info("Building full dashboard payload for {}", targetDate);
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
                log.debug("Building nutrition summary for {} with 7-day trend", targetDate);
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
                for (DailyFoodLog dailyLog : dailyLogs) {
                        String dateKey = dailyLog.getDateString();
                        if (dailyLog.getDailyTotals() != null) {
                                dailyCalories.put(dateKey, dailyLog.getDailyTotals().getTotalCalories());
                                dailyProtein.put(dateKey, dailyLog.getDailyTotals().getTotalProteinGrams());
                        }
                }

                // Group by meal type for today
                String todayKey = targetDate.format(DATE_FORMATTER);
                Map<String, Integer> mealTypeBreakdown = new LinkedHashMap<>();
                DailyFoodLog todayLog = dailyLogs.stream()
                                .filter(log -> todayKey.equals(log.getDateString()))
                                .findFirst()
                                .orElse(null);

                if (todayLog == null) {
                        todayLog = dailyFoodLogService.getDailyLog(todayKey);
                }

                if (todayLog != null && todayLog.getMeals() != null) {
                        for (Map.Entry<String, java.util.List<MealEntry>> mealGroup : todayLog.getMeals().entrySet()) {
                                int mealCalories = mealGroup.getValue().stream()
                                                .mapToInt(e -> e.getCalories() != null ? e.getCalories() : 0)
                                                .sum();
                                mealTypeBreakdown.put(mealGroup.getKey(), mealCalories);
                        }
                }

                int calorieGoal = todayLog != null && todayLog.getCalorieGoal() != null ? todayLog.getCalorieGoal() : CALORIE_GOAL;
                int proteinGoal = todayLog != null && todayLog.getProteinGoal() != null ? todayLog.getProteinGoal() : PROTEIN_GOAL;

                // Build response
                Map<String, Object> response = new LinkedHashMap<>();
                response.put("date", targetDate.format(DATE_FORMATTER));
                response.put("dailyCalories", dailyCalories);
                response.put("dailyProtein", dailyProtein);
                response.put("mealTypeBreakdown", mealTypeBreakdown);
                response.put("todayTotalCalories", dailyCalories.getOrDefault(todayKey, 0));
                response.put("todayTotalProtein", dailyProtein.getOrDefault(todayKey, 0));
                response.put("calorieGoal", calorieGoal);
                response.put("proteinGoal", proteinGoal);

                return response;
        }

        /**
         * Get spending summary for a specific month
         */
        public Map<String, Object> getSpendingSummary(YearMonth month) {
                log.debug("Building spending summary for {}", month);
                LocalDate monthStart = month.atDay(1);
                LocalDate monthEnd = month.atEndOfMonth();

                List<TransactionDTO> transactions = financeService.getTransactionsByDateStringRange(
                                monthStart.format(DateTimeFormatter.ISO_LOCAL_DATE),
                                monthEnd.format(DateTimeFormatter.ISO_LOCAL_DATE));

                // Calculate total spent
                BigDecimal totalExpenses = transactions.stream()
                                .filter(t -> "Expense".equals(t.getType()))
                                .map(t -> BigDecimal.valueOf(t.getAmount()))
                                .reduce(BigDecimal.ZERO, BigDecimal::add);

                // Group by category
                Map<String, BigDecimal> categorySpend = transactions.stream()
                                .filter(t -> "Expense".equals(t.getType()))
                                .collect(Collectors.groupingBy(
                                                TransactionDTO::getCategory,
                                                Collectors.reducing(BigDecimal.ZERO,
                                                                t -> BigDecimal.valueOf(t.getAmount()),
                                                                BigDecimal::add)));

                // Use the user's persisted monthly budget (falls back to 20 000 if never set)
                BigDecimal monthlyBudget = financeService.getMonthlyBudget();

                // Build response
                Map<String, Object> response = new LinkedHashMap<>();
                response.put("month", month.format(DateTimeFormatter.ofPattern("yyyy-MM")));
                response.put("totalSpent", totalExpenses.doubleValue());
                response.put("monthlyBudget", monthlyBudget.doubleValue());
                response.put("budgetRemaining", monthlyBudget.subtract(totalExpenses).doubleValue());
                response.put("budgetUtilization", monthlyBudget.compareTo(BigDecimal.ZERO) > 0
                                ? totalExpenses.divide(monthlyBudget, 4, java.math.RoundingMode.HALF_UP).doubleValue() * 100
                                : 0.0);
                response.put("categoryBreakdown", categorySpend.entrySet().stream()
                                .collect(Collectors.toMap(
                                                Map.Entry::getKey,
                                                e -> e.getValue().doubleValue())));
                response.put("transactionCount", transactions.size());

                return response;
        }

        /**
         * Learnings hub summary: today stats, 7-day timeline, coding counters.
         */
        @SuppressWarnings("unused")
        public LearningsSummaryResponse getLearningsSummary(LocalDate targetDate) {
                log.debug("Building learnings summary for {} (14-day window)", targetDate);
                LocalDate startDate = targetDate.minusDays(13); // 14 days total

                String userId = com.personal_dashboard.backend.security.UserContext.getRequiredUserId();
                List<Learning> rangeLearnings = learningRepository.findByUserIdAndDateRange(userId, startDate, targetDate);
                List<DailyTask> rangeTasks = dailyTaskRepository.findByUserIdAndDateRange(userId, startDate, targetDate.plusDays(1))
                                .stream()
                                .filter(t -> t.getItemType() == null || "TASK".equalsIgnoreCase(t.getItemType()))
                                .toList();
                List<DailyLog> rangeLogs = dailyLogRepository.findByUserIdAndDateRange(userId, startDate, targetDate);

                Map<LocalDate, List<Learning>> learningsByDate = rangeLearnings.stream()
                                .collect(Collectors.groupingBy(Learning::getDate));
                Map<LocalDate, List<DailyTask>> tasksByDate = rangeTasks.stream()
                                .collect(Collectors.groupingBy(DailyTask::getDate));
                Map<LocalDate, DailyLog> logsByDate = rangeLogs.stream()
                                .collect(Collectors.toMap(DailyLog::getDate, entry -> entry, (a, b) -> a));

                List<LearningsTimelineDay> timeline = new ArrayList<>();
                for (LocalDate date = startDate; !date.isAfter(targetDate); date = date.plusDays(1)) {
                        final LocalDate finalDate = date;
                        List<Learning> dayLearnings = learningsByDate.getOrDefault(date, List.of());
                        List<DailyTask> dayTasks = tasksByDate.getOrDefault(date, List.of());
                        int tasksCompleted = (int) dayTasks.stream()
                                        .filter(t -> t.getExcludedDates() == null
                                                        || !t.getExcludedDates().contains(finalDate))
                                        .filter(t -> {
                                                if (t.getRecurrenceFrequency() != null && !"NONE"
                                                                .equalsIgnoreCase(t.getRecurrenceFrequency())) {
                                                        return t.getCompletedDates() != null
                                                                        && t.getCompletedDates().contains(finalDate);
                                                }
                                                return Boolean.TRUE.equals(t.getCompleted());
                                        })
                                        .count();
                        int intensity = calculateIntensity(dayLearnings.size() + tasksCompleted);

                        timeline.add(LearningsTimelineDay.builder()
                                        .date(date.format(DATE_FORMATTER))
                                        .learningsCount(dayLearnings.size())
                                        .tasksCompleted(tasksCompleted)
                                        .intensity(intensity)
                                        .build());
                }

                List<Learning> todayLearnings = learningsByDate.getOrDefault(targetDate, List.of());
                List<DailyTask> todayTasks = dailyTaskRepository.findActiveTasks(userId, LocalDateTime.now().minusHours(48))
                                .stream()
                                .filter(t -> t.getItemType() == null || "TASK".equalsIgnoreCase(t.getItemType()))
                                .filter(t -> t.getExcludedDates() == null || !t.getExcludedDates().contains(targetDate))
                                .toList();
                int todayTasksCompleted = (int) todayTasks.stream()
                                .filter(t -> {
                                        if (t.getRecurrenceFrequency() != null
                                                        && !"NONE".equalsIgnoreCase(t.getRecurrenceFrequency())) {
                                                return t.getCompletedDates() != null
                                                                && t.getCompletedDates().contains(targetDate);
                                        }
                                        return Boolean.TRUE.equals(t.getCompleted());
                                })
                                .count();

                Map<String, Long> categoryCounts = todayLearnings.stream()
                                .collect(Collectors.groupingBy(Learning::getCategory, Collectors.counting()));
                List<LearningsCategoryCount> categories = categoryCounts.entrySet().stream()
                                .map(e -> LearningsCategoryCount.builder()
                                                .name(e.getKey())
                                                .count(e.getValue().intValue())
                                                .build())
                                .sorted(Comparator.comparing(LearningsCategoryCount::getCount).reversed())
                                .collect(Collectors.toList());

                int weeklyLearningCount = rangeLearnings.size();
                int streakDays = calculateActivityStreak(timeline);

                List<DailyTask> allTasks = dailyTaskRepository.findByUserId(userId).stream()
                                .filter(t -> t.getItemType() == null || "TASK".equalsIgnoreCase(t.getItemType()))
                                .toList();
                long totalTasksCompleted = allTasks.stream()
                                .filter(t -> Boolean.TRUE.equals(t.getCompleted()))
                                .count();
                long totalTasksCount = allTasks.size();

                long totalLearningsCount = learningRepository.countByUserId(userId);
                long totalPursuitsCount = learningPursuitRepository.countByUserId(userId);

                LearningsTodaySummary today = LearningsTodaySummary.builder()
                                .learningsCount(todayLearnings.size())
                                .tasksTotal(todayTasks.size())
                                .tasksCompleted(todayTasksCompleted)
                                .categories(categories)
                                .build();

                LearningsStatsSummary stats = LearningsStatsSummary.builder()
                                .weeklyLearningCount(weeklyLearningCount)
                                .streakDays(streakDays)
                                .totalTasksCompleted(totalTasksCompleted)
                                .totalTasksCount(totalTasksCount)
                                .totalLearningsCount(totalLearningsCount)
                                .totalPursuitsCount(totalPursuitsCount)
                                .build();

                return LearningsSummaryResponse.builder()
                                .date(targetDate.format(DATE_FORMATTER))
                                .today(today)
                                .timeline(timeline)
                                .stats(stats)
                                .build();
        }

        private int calculateActivityStreak(List<LearningsTimelineDay> timeline) {
                if (timeline.isEmpty())
                        return 0;
                int streak = 0;
                for (int i = timeline.size() - 1; i >= 0; i--) {
                        LearningsTimelineDay day = timeline.get(i);
                        boolean active = (day.getLearningsCount() != null && day.getLearningsCount() > 0)
                                        || (day.getTasksCompleted() != null && day.getTasksCompleted() > 0)
                                        || (day.getIntensity() != null && day.getIntensity() > 0);
                        if (active) {
                                streak++;
                        } else {
                                break;
                        }
                }
                return streak;
        }
}
