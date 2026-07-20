package com.personal_dashboard.backend.service;

import com.personal_dashboard.backend.dto.RingDayResponse;
import com.personal_dashboard.backend.dto.request.ManualMoveRequest;
import com.personal_dashboard.backend.model.DailyFinancialLog;
import com.personal_dashboard.backend.model.DailyFoodLog;
import com.personal_dashboard.backend.model.DailyRing;
import com.personal_dashboard.backend.model.MealEntry;
import com.personal_dashboard.backend.model.SleepLog;
import com.personal_dashboard.backend.model.StravaActivity;
import com.personal_dashboard.backend.model.StreakState;
import com.personal_dashboard.backend.model.UserAccount;
import com.personal_dashboard.backend.model.UserAccount.RingTargets;
import com.personal_dashboard.backend.repository.DailyFinancialLogRepository;
import com.personal_dashboard.backend.repository.DailyFoodLogRepository;
import com.personal_dashboard.backend.repository.DailyRingRepository;
import com.personal_dashboard.backend.repository.SleepLogRepository;
import com.personal_dashboard.backend.repository.StravaActivityRepository;
import com.personal_dashboard.backend.repository.StreakStateRepository;
import com.personal_dashboard.backend.repository.UserAccountRepository;
import com.personal_dashboard.backend.security.UserContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The Three Non-Negotiables engine. Each ring day is a pure function of the
 * day's source data (sleep_logs, focus_sessions, strava_activities, plus the
 * day's manual move log) — {@link #computeDay} is idempotent and replayable.
 *
 * Streaks, freezes, and XP are never tracked incrementally: {@link #recomputeStreak}
 * replays the entire daily_rings history on every read, the same
 * compute-on-read pattern MindService uses to resurface parked worries. No scheduler.
 *
 * The ring day rolls over at the user's rollover hour (default 04:00
 * Asia/Kolkata), not midnight: work finished at 01:00 belongs to the previous
 * day, and sleep logged at 07:00 closes the previous night's ring.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RingsService {

    private static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter MONTH_KEY = DateTimeFormatter.ofPattern("yyyy-MM");

    static final int XP_PER_RING = 20;
    static final int XP_PERFECT_BONUS = 40;
    static final int FREEZES_PER_MONTH = 2;
    // Fuel XP (Phase 2) — mirrored by FUEL_XP_* in dashboard_ui/src/lib/insights/nutrition.ts.
    static final int FUEL_XP_PER_MEAL = 10;
    static final int FUEL_XP_PROTEIN_GOAL = 25;
    // Finance XP (Phase 4) — mirrored by FINANCE_XP_NO_SPEND in dashboard_ui/src/lib/insights/finance.ts.
    static final int FINANCE_XP_NO_SPEND = 30;

    public static final String MOVE_SOURCE_STRAVA = "STRAVA";
    public static final String MOVE_SOURCE_MANUAL = "MANUAL";

    private final SleepLogRepository sleepLogRepository;
    private final FocusSessionService focusSessionService;
    private final StravaActivityRepository stravaActivityRepository;
    private final UserAccountRepository userAccountRepository;
    private final DailyRingRepository dailyRingRepository;
    private final StreakStateRepository streakStateRepository;
    private final DailyFoodLogRepository dailyFoodLogRepository;
    private final DailyFinancialLogRepository dailyFinancialLogRepository;

    /** Injectable for tests; defaults to the system clock. */
    private Clock clock = Clock.system(ZONE);

    void setClock(Clock clock) {
        this.clock = clock;
    }

    // ---------- Reads ----------

    public RingDayResponse getToday() {
        LocalDate today = ringDayToday();
        // Yesterday too: a sleep log or late-night focus session commonly lands
        // after that ring day was last computed.
        computeDay(today.minusDays(1));
        DailyRing ring = computeDay(today);
        StreakState streak = recomputeStreak();
        if (ring == null) {
            // Nothing logged yet today — return an honest empty day, not a fake one.
            ring = emptyRing(UserContext.getRequiredUserId(), today);
        } else {
            ring = dailyRingRepository.findByUserIdAndDate(ring.getUserId(), today).orElse(ring);
        }
        return buildDayResponse(ring, streak);
    }

    public List<DailyRing> getRange(LocalDate startDate, LocalDate endDate) {
        String userId = UserContext.getRequiredUserId();
        LocalDate today = ringDayToday();
        LocalDate computableEnd = endDate.isBefore(today) ? endDate : today;

        recomputeStaleDays(userId, startDate, computableEnd, today);
        recomputeStreak();
        return dailyRingRepository.findByUserIdAndDateRange(userId, startDate, endDate);
    }

    public StreakState getStreak() {
        LocalDate today = ringDayToday();
        computeDay(today.minusDays(1));
        computeDay(today);
        return recomputeStreak();
    }

    // ---------- Writes ----------

    public DailyRing logManualMove(ManualMoveRequest request) {
        String userId = UserContext.getRequiredUserId();
        LocalDate date = LocalDate.parse(request.getDate());

        DailyRing ring = dailyRingRepository.findByUserIdAndDate(userId, date)
                .orElseGet(() -> DailyRing.builder().userId(userId).date(date).build());
        ring.setManualMoveMinutes(request.getMinutes());
        ring.setManualMoveType(request.getActivityType());
        ring.setMoveNote(request.getNote() != null && !request.getNote().isBlank()
                ? request.getNote().trim() : null);
        dailyRingRepository.save(ring);
        log.info("Manual move logged for {}: {} min ({})", date, request.getMinutes(), request.getActivityType());

        DailyRing computed = computeDay(date);
        recomputeStreak();
        return computed;
    }

    public DailyRing undoManualMove(LocalDate date) {
        String userId = UserContext.getRequiredUserId();
        DailyRing ring = dailyRingRepository.findByUserIdAndDate(userId, date)
                .orElseThrow(() -> new IllegalArgumentException("No ring day found for date: " + date));
        ring.setManualMoveMinutes(null);
        ring.setManualMoveType(null);
        ring.setMoveNote(null);
        dailyRingRepository.save(ring);
        log.info("Manual move removed for {}", date);

        DailyRing computed = computeDay(date);
        recomputeStreak();
        return computed != null ? computed
                : dailyRingRepository.findByUserIdAndDate(userId, date).orElse(null);
    }

    public DailyRing recomputeDay(LocalDate date) {
        DailyRing computed = computeDay(date);
        recomputeStreak();
        return computed;
    }

    // ---------- Core computation ----------

    /**
     * Rebuild one ring day from its sources. Pure and idempotent: running it
     * twice produces the same document. Returns null (and persists nothing)
     * when the day has no source data at all — days before the user's first
     * log must not exist, or they would poison the streak replay.
     */
    public DailyRing computeDay(LocalDate date) {
        String userId = UserContext.getRequiredUserId();
        RingTargets targets = effectiveTargets(userId);

        DailyRing ring = dailyRingRepository.findByUserIdAndDate(userId, date)
                .orElseGet(() -> DailyRing.builder().userId(userId).date(date).build());

        // REST — SleepLog.date is the wake-up morning, so the night that ended
        // this morning is this ring day's night.
        int restMinutes = sleepLogRepository.findByUserIdAndDate(userId, date)
                .map(SleepLog::getDurationMinutes)
                .orElse(0);

        // DEEP — completed focus minutes, grouped by ring day (04:00 rollover).
        long deepMinutes = focusSessionService
                .getRingDayMinutes(userId, date, date, targets.getDayRolloverHour())
                .getOrDefault(date, 0L);

        // MOVE — Strava vs the manual log: the larger wins.
        double stravaMinutes = stravaActivityRepository.findByUserIdAndDateBetween(userId, date, date)
                .stream()
                .map(StravaActivity::getMovingTimeMinutes)
                .filter(Objects::nonNull)
                .mapToDouble(Double::doubleValue)
                .sum();
        int manualMinutes = ring.getManualMoveMinutes() != null ? ring.getManualMoveMinutes() : 0;
        int moveMinutes;
        String moveSource;
        if (manualMinutes <= 0 && stravaMinutes <= 0) {
            moveMinutes = 0;
            moveSource = null;
        } else if (manualMinutes > stravaMinutes) {
            moveMinutes = manualMinutes;
            moveSource = MOVE_SOURCE_MANUAL;
        } else {
            moveMinutes = (int) Math.round(stravaMinutes);
            moveSource = MOVE_SOURCE_STRAVA;
        }

        // Fuel XP — +10 per logged meal, +25 when the day's protein goal was
        // met. Banked here (not display-only) so the header line and the level
        // bar can never disagree.
        int fuelXp = computeFuelXp(userId, date);

        // Finance XP — a completed no-spend day is the single most
        // behaviour-changing signal on /finance; restraint earns, spending never does.
        int financeXp = computeFinanceXp(userId, date);

        boolean hasAnyData = restMinutes > 0 || deepMinutes > 0 || moveMinutes > 0 || fuelXp > 0 || financeXp > 0;
        if (!hasAnyData && ring.getId() == null) {
            return null;
        }

        ring.setRestMinutes(restMinutes);
        ring.setRestTargetMinutes(targets.getSleepTargetMinutes());
        ring.setRestClosed(restMinutes >= targets.getSleepTargetMinutes());

        ring.setDeepMinutes((int) deepMinutes);
        ring.setDeepTargetMinutes(targets.getFocusTargetMinutes());
        ring.setDeepClosed(deepMinutes >= targets.getFocusTargetMinutes());

        ring.setMoveMinutes(moveMinutes);
        ring.setMoveTargetMinutes(targets.getMoveTargetMinutes());
        ring.setMoveClosed(moveMinutes >= targets.getMoveTargetMinutes());
        ring.setMoveSource(moveSource);

        int ringsClosed = (ring.isRestClosed() ? 1 : 0)
                + (ring.isDeepClosed() ? 1 : 0)
                + (ring.isMoveClosed() ? 1 : 0);
        ring.setRingsClosed(ringsClosed);
        ring.setPerfect(ringsClosed == 3);
        ring.setFuelXp(fuelXp);
        ring.setFinanceXp(financeXp);
        // Base XP; the streak replay zeroes it if a freeze ends up covering this day.
        ring.setXpEarned(ringsClosed * XP_PER_RING + (ring.isPerfect() ? XP_PERFECT_BONUS : 0) + fuelXp + financeXp);
        ring.setComputedAt(Instant.now(clock));

        try {
            return dailyRingRepository.save(ring);
        } catch (DuplicateKeyException e) {
            // Another concurrent computeDay() call for the same userId+date won
            // the insert race first. Fold our freshly computed fields onto that
            // document instead of failing the request.
            DailyRing existing = dailyRingRepository.findByUserIdAndDate(userId, date).orElseThrow(() -> e);
            ring.setId(existing.getId());
            ring.setCreatedAt(existing.getCreatedAt());
            return dailyRingRepository.save(ring);
        }
    }

    /**
     * Replay the full daily_rings history and overwrite the user's StreakState.
     * Never incremental — incremental streak counters drift and are impossible
     * to debug. Freeze consumption, frozen flags, and per-day XP are all
     * re-derived here, so backfilling a day retroactively un-freezes it and
     * returns the freeze token.
     */
    public StreakState recomputeStreak() {
        String userId = UserContext.getRequiredUserId();
        LocalDate today = ringDayToday();

        List<DailyRing> rings = dailyRingRepository.findByUserIdOrderByDateAsc(userId);
        StreakState state = streakStateRepository.findByUserId(userId)
                .orElseGet(() -> StreakState.builder().userId(userId).build());

        if (rings.isEmpty()) {
            state.setCurrentStreak(0);
            state.setLongestStreak(0);
            state.setLastPerfectDate(null);
            state.setFreezesAvailable(FREEZES_PER_MONTH);
            state.setFreezesUsedThisMonth(0);
            state.setFreezeMonthKey(today.format(MONTH_KEY));
            state.setTotalXp(0);
            state.setLevel(1);
            state.setPerfectDaysAllTime(0);
            return streakStateRepository.save(state);
        }

        Map<LocalDate, DailyRing> byDate = new HashMap<>();
        rings.forEach(r -> byDate.put(r.getDate(), r));
        LocalDate first = rings.get(0).getDate();

        int streak = 0;
        int longest = 0;
        int perfectDays = 0;
        long xpFromDays = 0;
        LocalDate lastPerfect = null;
        String monthKey = null;
        int freezesLeft = 0;
        int freezesUsed = 0;
        List<DailyRing> dirty = new ArrayList<>();

        for (LocalDate d = first; !d.isAfter(today); d = d.plusDays(1)) {
            String dMonth = d.format(MONTH_KEY);
            if (!dMonth.equals(monthKey)) {
                monthKey = dMonth;
                freezesLeft = FREEZES_PER_MONTH;
                freezesUsed = 0;
            }

            DailyRing ring = byDate.get(d);
            boolean qualified = ring != null && ring.getRingsClosed() == 3;
            boolean frozen = false;

            if (qualified) {
                streak++;
                perfectDays++;
                lastPerfect = d;
            } else if (d.isBefore(today)) {
                // A past day rolled over unqualified: a freeze holds the chain
                // (without extending it) if one is left and there is a chain to hold.
                if (streak > 0 && freezesLeft > 0) {
                    freezesLeft--;
                    freezesUsed++;
                    frozen = true;
                } else {
                    streak = 0;
                }
            }
            // else: today, still in progress — at risk, never broken, never frozen.

            longest = Math.max(longest, streak);

            int xp = frozen ? 0
                    : ring != null
                        ? ring.getRingsClosed() * XP_PER_RING + (qualified ? XP_PERFECT_BONUS : 0) + ring.getFuelXp() + ring.getFinanceXp()
                        : 0;
            xpFromDays += xp;

            if (frozen && ring == null) {
                // A gap day a freeze covered needs a document so the range and
                // journey views can show it as frozen rather than missed.
                ring = emptyRing(userId, d);
            }
            if (ring != null && (ring.isFrozen() != frozen || ring.getXpEarned() != xp)) {
                ring.setFrozen(frozen);
                ring.setXpEarned(xp);
                dirty.add(ring);
            }
        }

        if (!dirty.isEmpty()) {
            dailyRingRepository.saveAll(dirty);
        }

        long milestoneXp = (longest >= 7 ? 100 : 0) + (longest >= 30 ? 500 : 0) + (longest >= 100 ? 2000 : 0);
        long totalXp = xpFromDays + milestoneXp;

        state.setCurrentStreak(streak);
        state.setLongestStreak(longest);
        state.setLastPerfectDate(lastPerfect);
        state.setFreezesAvailable(freezesLeft);
        state.setFreezesUsedThisMonth(freezesUsed);
        state.setFreezeMonthKey(today.format(MONTH_KEY));
        state.setTotalXp(totalXp);
        state.setLevel(levelFor(totalXp));
        state.setPerfectDaysAllTime(perfectDays);
        return streakStateRepository.save(state);
    }

    // ---------- Helpers ----------

    /** The current ring day: before the rollover hour, it is still "yesterday". */
    public LocalDate ringDayToday() {
        String userId = UserContext.getRequiredUserId();
        int rolloverHour = effectiveTargets(userId).getDayRolloverHour();
        ZonedDateTime now = ZonedDateTime.now(clock.withZone(ZONE));
        LocalDate day = now.toLocalDate();
        return now.getHour() < rolloverHour ? day.minusDays(1) : day;
    }

    /** +10 per logged meal, +25 when protein met the day's goal — from daily_food_logs. */
    private int computeFuelXp(String userId, LocalDate date) {
        return dailyFoodLogRepository.findByUserIdAndDateString(userId, date.toString())
                .map(log -> {
                    List<MealEntry> meals = log.getMeals() == null
                            ? List.<MealEntry>of()
                            : log.getMeals().values().stream().flatMap(List::stream).toList();
                    int proteinSum = meals.stream()
                            .mapToInt(m -> m.getProteinGrams() != null ? m.getProteinGrams() : 0)
                            .sum();
                    boolean proteinGoalMet = log.getProteinGoal() != null && log.getProteinGoal() > 0
                            && proteinSum >= log.getProteinGoal();
                    return meals.size() * FUEL_XP_PER_MEAL + (proteinGoalMet ? FUEL_XP_PROTEIN_GOAL : 0);
                })
                .orElse(0);
    }


    /**
     * +30 for a completed no-spend day: the ring day has rolled over, the day
     * has zero expense transactions, and the month has at least one financial
     * log (proof the tracker was in use — absence of data alone earns nothing).
     */
    private int computeFinanceXp(String userId, LocalDate date) {
        if (!date.isBefore(ringDayToday())) {
            return 0; // today is still in progress — you haven't finished not-spending yet
        }
        String monthStart = date.withDayOfMonth(1).toString();
        String monthEnd = date.withDayOfMonth(date.lengthOfMonth()).toString();
        List<DailyFinancialLog> monthLogs =
                dailyFinancialLogRepository.findByUserIdAndDateStringBetween(userId, monthStart, monthEnd);
        if (monthLogs.isEmpty()) {
            return 0;
        }
        boolean hasExpenseThatDay = monthLogs.stream()
                .filter(l -> date.toString().equals(l.getDateString()))
                .anyMatch(l -> l.getTransactions() != null && l.getTransactions().values().stream()
                        .flatMap(List::stream)
                        .anyMatch(tx -> "Expense".equalsIgnoreCase(tx.getType())));
        return hasExpenseThatDay ? 0 : FINANCE_XP_NO_SPEND;
    }

    public RingTargets effectiveTargets(String userId) {
        return userAccountRepository.findById(userId)
                .map(UserAccount::getRingTargets)
                .map(RingTargets::resolved)
                .orElseGet(RingTargets::defaults);
    }

    public RingDayResponse buildDayResponse(DailyRing ring, StreakState streak) {
        long floor = levelFloorXp(streak.getLevel());
        long ceiling = levelFloorXp(streak.getLevel() + 1);
        return RingDayResponse.builder()
                .ring(ring)
                .streak(streak)
                .xpIntoLevel(streak.getTotalXp() - floor)
                .xpForNextLevel(ceiling - floor)
                .build();
    }

    static int levelFor(long totalXp) {
        return (int) Math.floor(Math.sqrt(totalXp / 100.0)) + 1;
    }

    /** XP where the given level begins: 100 * (level - 1)^2. */
    static long levelFloorXp(int level) {
        long base = level - 1L;
        return 100L * base * base;
    }

    /**
     * Recompute days in [startDate, endDate] that are missing or whose
     * computedAt predates their source data; today and yesterday always.
     */
    private void recomputeStaleDays(String userId, LocalDate startDate, LocalDate endDate, LocalDate today) {
        if (startDate.isAfter(endDate)) {
            return;
        }
        Map<LocalDate, DailyRing> existing = new HashMap<>();
        dailyRingRepository.findByUserIdAndDateRange(userId, startDate, endDate)
                .forEach(r -> existing.put(r.getDate(), r));

        Map<LocalDate, Instant> sourceTouched = latestSourceUpdates(userId, startDate, endDate);

        for (LocalDate d = startDate; !d.isAfter(endDate); d = d.plusDays(1)) {
            DailyRing ring = existing.get(d);
            boolean always = !d.isBefore(today.minusDays(1));
            Instant touched = sourceTouched.get(d);
            boolean missing = ring == null && touched != null;
            boolean stale = ring != null && touched != null
                    && (ring.getComputedAt() == null || ring.getComputedAt().isBefore(touched));
            if (always || missing || stale) {
                computeDay(d);
            }
        }
    }

    /** Latest source-document write per day, across sleep, focus, and Strava. */
    private Map<LocalDate, Instant> latestSourceUpdates(String userId, LocalDate startDate, LocalDate endDate) {
        Map<LocalDate, Instant> latest = new HashMap<>();
        sleepLogRepository.findByUserIdAndDateRange(userId, startDate, endDate).forEach(s ->
                mergeLatest(latest, s.getDate(), s.getUpdatedAt() != null ? s.getUpdatedAt() : s.getCreatedAt()));
        stravaActivityRepository.findByUserIdAndDateBetween(userId, startDate, endDate).forEach(a ->
                mergeLatest(latest, a.getDate(), a.getUpdatedAt() != null ? a.getUpdatedAt() : a.getCreatedAt()));
        dailyFoodLogRepository.findByUserIdAndDateStringRange(userId, startDate.toString(), endDate.toString())
                .forEach(f -> mergeLatest(latest, f.getDate() != null ? f.getDate() : parseDateString(f),
                        f.getUpdatedAt() != null ? f.getUpdatedAt() : f.getCreatedAt()));
        dailyFinancialLogRepository.findByUserIdAndDateStringBetween(userId, startDate.toString(), endDate.toString())
                .forEach(f -> mergeLatest(latest, parseFinDateString(f),
                        f.getUpdatedAt() != null ? f.getUpdatedAt() : f.getCreatedAt()));
        // Focus sessions carry no per-day write timestamp we can range-query
        // cheaply, so any day with focus minutes counts as touched "now" only
        // when it has no ring yet; today/yesterday are recomputed regardless.
        int rolloverHour = effectiveTargets(userId).getDayRolloverHour();
        focusSessionService.getRingDayMinutes(userId, startDate, endDate, rolloverHour)
                .forEach((date, minutes) -> {
                    if (minutes > 0) {
                        latest.putIfAbsent(date, Instant.EPOCH.plusSeconds(1));
                    }
                });
        return latest;
    }

    private LocalDate parseDateString(DailyFoodLog foodLog) {
        try {
            return foodLog.getDateString() != null ? LocalDate.parse(foodLog.getDateString()) : null;
        } catch (Exception e) {
            return null;
        }
    }

    private LocalDate parseFinDateString(DailyFinancialLog finLog) {
        try {
            return finLog.getDateString() != null ? LocalDate.parse(finLog.getDateString()) : null;
        } catch (Exception e) {
            return null;
        }
    }

    private void mergeLatest(Map<LocalDate, Instant> latest, LocalDate date, Instant candidate) {
        if (date == null || candidate == null) {
            return;
        }
        latest.merge(date, candidate, (a, b) -> a.isAfter(b) ? a : b);
    }

    private DailyRing emptyRing(String userId, LocalDate date) {
        RingTargets targets = effectiveTargets(userId);
        return DailyRing.builder()
                .userId(userId)
                .date(date)
                .restTargetMinutes(targets.getSleepTargetMinutes())
                .deepTargetMinutes(targets.getFocusTargetMinutes())
                .moveTargetMinutes(targets.getMoveTargetMinutes())
                .computedAt(Instant.now(clock))
                .build();
    }
}
