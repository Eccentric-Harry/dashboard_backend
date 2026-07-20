package com.personal_dashboard.backend.service;

import com.personal_dashboard.backend.dto.request.ManualMoveRequest;
import com.personal_dashboard.backend.model.DailyFinancialLog;
import com.personal_dashboard.backend.model.DailyFoodLog;
import com.personal_dashboard.backend.model.DailyRing;
import com.personal_dashboard.backend.model.FocusSession;
import com.personal_dashboard.backend.model.FinancialTransaction;
import com.personal_dashboard.backend.model.FocusSessionStatus;
import com.personal_dashboard.backend.model.MealEntry;
import com.personal_dashboard.backend.model.SleepLog;
import com.personal_dashboard.backend.model.StravaActivity;
import com.personal_dashboard.backend.model.StreakState;
import com.personal_dashboard.backend.repository.DailyFinancialLogRepository;
import com.personal_dashboard.backend.repository.DailyFoodLogRepository;
import com.personal_dashboard.backend.repository.DailyRingRepository;
import com.personal_dashboard.backend.repository.FocusSessionRepository;
import com.personal_dashboard.backend.repository.SleepLogRepository;
import com.personal_dashboard.backend.repository.StravaActivityRepository;
import com.personal_dashboard.backend.repository.StreakStateRepository;
import com.personal_dashboard.backend.repository.UserAccountRepository;
import com.personal_dashboard.backend.security.UserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * The rings engine is tested against in-memory fakes of both new repositories
 * so the streak replay can be exercised end to end: computeDay writes real
 * documents into the fake store and recomputeStreak replays them.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RingsServiceTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");
    private static final String USER = "user-a";
    private static final String OTHER_USER = "user-b";
    /** Fixed "now": 2026-07-19 12:00 IST — ring day 2026-07-19. */
    private static final LocalDate TODAY = LocalDate.of(2026, 7, 19);

    @Mock private SleepLogRepository sleepLogRepository;
    @Mock private FocusSessionService focusSessionService;
    @Mock private StravaActivityRepository stravaActivityRepository;
    @Mock private UserAccountRepository userAccountRepository;
    @Mock private DailyRingRepository dailyRingRepository;
    @Mock private StreakStateRepository streakStateRepository;
    @Mock private DailyFoodLogRepository dailyFoodLogRepository;
    @Mock private DailyFinancialLogRepository dailyFinancialLogRepository;

    private RingsService ringsService;

    // In-memory stores backing the mocked repositories.
    private final Map<String, Map<LocalDate, DailyRing>> ringStore = new HashMap<>();
    private final Map<String, StreakState> streakStore = new HashMap<>();
    private final Map<String, Map<LocalDate, SleepLog>> sleepStore = new HashMap<>();
    private final Map<String, Map<LocalDate, Long>> focusStore = new HashMap<>();
    private final Map<String, Map<LocalDate, List<StravaActivity>>> stravaStore = new HashMap<>();
    private final Map<String, Map<String, DailyFoodLog>> foodStore = new HashMap<>();
    private final Map<String, Map<String, DailyFinancialLog>> finStore = new HashMap<>();
    private final AtomicInteger idSequence = new AtomicInteger();

    @BeforeEach
    void setUp() {
        UserContext.setUserId(USER);
        ringsService = new RingsService(
                sleepLogRepository, focusSessionService, stravaActivityRepository,
                userAccountRepository, dailyRingRepository, streakStateRepository,
                dailyFoodLogRepository, dailyFinancialLogRepository);
        setNow(TODAY, 12);

        when(userAccountRepository.findById(anyString())).thenReturn(Optional.empty());

        when(dailyRingRepository.findByUserIdAndDate(anyString(), any())).thenAnswer(inv ->
                Optional.ofNullable(ringsFor(inv.getArgument(0)).get(inv.getArgument(1))));
        when(dailyRingRepository.save(any(DailyRing.class))).thenAnswer(inv -> {
            DailyRing ring = inv.getArgument(0);
            if (ring.getId() == null) {
                ring.setId("ring-" + idSequence.incrementAndGet());
            }
            ringsFor(ring.getUserId()).put(ring.getDate(), ring);
            return ring;
        });
        when(dailyRingRepository.saveAll(any())).thenAnswer(inv -> {
            Iterable<DailyRing> rings = inv.getArgument(0);
            List<DailyRing> saved = new ArrayList<>();
            for (DailyRing ring : rings) {
                if (ring.getId() == null) {
                    ring.setId("ring-" + idSequence.incrementAndGet());
                }
                ringsFor(ring.getUserId()).put(ring.getDate(), ring);
                saved.add(ring);
            }
            return saved;
        });
        when(dailyRingRepository.findByUserIdOrderByDateAsc(anyString())).thenAnswer(inv ->
                ringsFor(inv.getArgument(0)).values().stream()
                        .sorted(Comparator.comparing(DailyRing::getDate))
                        .toList());
        when(dailyRingRepository.findByUserIdAndDateRange(anyString(), any(), any())).thenAnswer(inv -> {
            LocalDate start = inv.getArgument(1);
            LocalDate end = inv.getArgument(2);
            return ringsFor(inv.<String>getArgument(0)).values().stream()
                    .filter(r -> !r.getDate().isBefore(start) && !r.getDate().isAfter(end))
                    .sorted(Comparator.comparing(DailyRing::getDate))
                    .toList();
        });

        when(streakStateRepository.findByUserId(anyString())).thenAnswer(inv ->
                Optional.ofNullable(streakStore.get(inv.<String>getArgument(0))));
        when(streakStateRepository.save(any(StreakState.class))).thenAnswer(inv -> {
            StreakState state = inv.getArgument(0);
            streakStore.put(state.getUserId(), state);
            return state;
        });

        when(sleepLogRepository.findByUserIdAndDate(anyString(), any())).thenAnswer(inv ->
                Optional.ofNullable(sleepStore.getOrDefault(inv.<String>getArgument(0), Map.of())
                        .get(inv.<LocalDate>getArgument(1))));
        when(sleepLogRepository.findByUserIdAndDateRange(anyString(), any(), any())).thenAnswer(inv -> {
            LocalDate start = inv.getArgument(1);
            LocalDate end = inv.getArgument(2);
            return sleepStore.getOrDefault(inv.<String>getArgument(0), Map.of()).values().stream()
                    .filter(s -> !s.getDate().isBefore(start) && !s.getDate().isAfter(end))
                    .toList();
        });

        when(focusSessionService.getRingDayMinutes(anyString(), any(), any(), anyInt())).thenAnswer(inv -> {
            LocalDate start = inv.getArgument(1);
            LocalDate end = inv.getArgument(2);
            Map<LocalDate, Long> all = focusStore.getOrDefault(inv.<String>getArgument(0), Map.of());
            Map<LocalDate, Long> window = new HashMap<>();
            all.forEach((date, minutes) -> {
                if (!date.isBefore(start) && !date.isAfter(end)) {
                    window.put(date, minutes);
                }
            });
            return window;
        });

        when(stravaActivityRepository.findByUserIdAndDateBetween(anyString(), any(), any())).thenAnswer(inv -> {
            LocalDate start = inv.getArgument(1);
            LocalDate end = inv.getArgument(2);
            return stravaStore.getOrDefault(inv.<String>getArgument(0), Map.of()).entrySet().stream()
                    .filter(e -> !e.getKey().isBefore(start) && !e.getKey().isAfter(end))
                    .flatMap(e -> e.getValue().stream())
                    .toList();
        });

        when(dailyFoodLogRepository.findByUserIdAndDateString(anyString(), anyString())).thenAnswer(inv ->
                Optional.ofNullable(foodStore.getOrDefault(inv.<String>getArgument(0), Map.of())
                        .get(inv.<String>getArgument(1))));
        when(dailyFoodLogRepository.findByUserIdAndDateStringRange(anyString(), anyString(), anyString()))
                .thenReturn(List.of());

        when(dailyFinancialLogRepository.findByUserIdAndDateStringBetween(anyString(), anyString(), anyString()))
                .thenAnswer(inv -> {
                    String from = inv.getArgument(1);
                    String to = inv.getArgument(2);
                    return finStore.getOrDefault(inv.<String>getArgument(0), Map.of()).entrySet().stream()
                            .filter(e -> e.getKey().compareTo(from) >= 0 && e.getKey().compareTo(to) <= 0)
                            .map(Map.Entry::getValue)
                            .toList();
                });
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    // ---------- computeDay ----------

    @Test
    void perfectDayClosesAllRingsAndAwardsFullXp() {
        givenSleep(USER, TODAY, 460);
        givenFocus(USER, TODAY, 130);
        givenStrava(USER, TODAY, 40.0);

        DailyRing ring = ringsService.computeDay(TODAY);

        assertTrue(ring.isRestClosed());
        assertTrue(ring.isDeepClosed());
        assertTrue(ring.isMoveClosed());
        assertEquals(3, ring.getRingsClosed());
        assertTrue(ring.isPerfect());
        assertEquals(100, ring.getXpEarned());
        assertEquals("STRAVA", ring.getMoveSource());
    }

    @Test
    void twoOfThreeDayIsNotPerfect() {
        givenSleep(USER, TODAY, 460);
        givenFocus(USER, TODAY, 45); // under the 120m target

        DailyRing ring = ringsService.computeDay(TODAY);

        assertTrue(ring.isRestClosed());
        assertFalse(ring.isDeepClosed());
        assertFalse(ring.isMoveClosed());
        assertEquals(1, ring.getRingsClosed());
        assertFalse(ring.isPerfect());
        assertEquals(20, ring.getXpEarned());
    }

    @Test
    void manualMoveBeatsSmallerStravaActivity() {
        givenStrava(USER, TODAY, 30.0);
        ringsService.logManualMove(ManualMoveRequest.builder()
                .date(TODAY.toString()).minutes(45).activityType("Walk").note("evening walk").build());

        DailyRing ring = ringsFor(USER).get(TODAY);
        assertEquals(45, ring.getMoveMinutes());
        assertEquals("MANUAL", ring.getMoveSource());
        assertEquals("evening walk", ring.getMoveNote());
        assertTrue(ring.isMoveClosed());
    }

    @Test
    void largerStravaActivityBeatsManualMove() {
        givenStrava(USER, TODAY, 60.0);
        ringsService.logManualMove(ManualMoveRequest.builder()
                .date(TODAY.toString()).minutes(45).activityType("Gym").build());

        DailyRing ring = ringsFor(USER).get(TODAY);
        assertEquals(60, ring.getMoveMinutes());
        assertEquals("STRAVA", ring.getMoveSource());
        // The manual log survives for replay even when Strava wins the max.
        assertEquals(45, ring.getManualMoveMinutes());
    }

    @Test
    void undoManualMoveReopensTheRing() {
        ringsService.logManualMove(ManualMoveRequest.builder()
                .date(TODAY.toString()).minutes(30).activityType("Walk").build());
        assertTrue(ringsFor(USER).get(TODAY).isMoveClosed());

        ringsService.undoManualMove(TODAY);

        DailyRing ring = ringsFor(USER).get(TODAY);
        assertFalse(ring.isMoveClosed());
        assertNull(ring.getManualMoveMinutes());
        assertNull(ring.getMoveSource());
    }

    @Test
    void computeDayIsIdempotent() {
        givenSleep(USER, TODAY, 460);
        givenFocus(USER, TODAY, 130);

        DailyRing first = ringsService.computeDay(TODAY);
        DailyRing second = ringsService.computeDay(TODAY);

        assertEquals(first.getId(), second.getId());
        assertEquals(1, ringsFor(USER).size());
        assertEquals(first.getRingsClosed(), second.getRingsClosed());
        assertEquals(first.getXpEarned(), second.getXpEarned());
    }

    @Test
    void dayWithNoSourceDataCreatesNoDocument() {
        assertNull(ringsService.computeDay(TODAY));
        assertTrue(ringsFor(USER).isEmpty());
    }

    // ---------- fuel XP ----------

    @Test
    void mealsAndProteinGoalEarnFuelXp() {
        givenSleep(USER, TODAY, 460); // rest closed → +20 ring XP
        givenMeals(USER, TODAY, 90, 3, 40, 30, 25); // goal 90, 3 meals, 95g total

        DailyRing ring = ringsService.computeDay(TODAY);

        assertEquals(3 * 10 + 25, ring.getFuelXp());
        assertEquals(20 + 55, ring.getXpEarned());
    }

    @Test
    void proteinUnderGoalEarnsOnlyMealXp() {
        givenMeals(USER, TODAY, 120, 2, 30, 25); // 55g of 120 — goal missed

        DailyRing ring = ringsService.computeDay(TODAY);

        assertEquals(20, ring.getFuelXp());
        assertEquals(0, ring.getRingsClosed());
        assertEquals(20, ring.getXpEarned()); // fuel only — no rings closed
    }

    @Test
    void mealsAloneCreateARingDayDocument() {
        givenMeals(USER, TODAY, 0, 1, 20);

        DailyRing ring = ringsService.computeDay(TODAY);

        assertNotNull(ring); // a logged meal is real activity — the day exists
        assertEquals(10, ring.getFuelXp());
    }

    @Test
    void frozenDayZeroesFuelXpToo() {
        seedPerfectDays(USER, TODAY.minusDays(3), TODAY.minusDays(2));
        seedDay(USER, TODAY.minusDays(1), 1, false);
        ringsFor(USER).get(TODAY.minusDays(1)).setFuelXp(35); // meals were logged that day
        seedPerfectDays(USER, TODAY, TODAY);

        StreakState state = ringsService.recomputeStreak();

        DailyRing frozen = ringsFor(USER).get(TODAY.minusDays(1));
        assertTrue(frozen.isFrozen());
        assertEquals(0, frozen.getXpEarned()); // the freeze zeroes the whole day, fuel included
        assertEquals(300, state.getTotalXp()); // 3 perfect days, nothing else
    }

    @Test
    void replayBanksFuelXpFromUnfrozenDays() {
        seedPerfectDays(USER, TODAY.minusDays(1), TODAY);
        ringsFor(USER).get(TODAY).setFuelXp(30);

        StreakState state = ringsService.recomputeStreak();

        assertEquals(230, state.getTotalXp()); // 100 + 100 + 30 fuel
    }


    // ---------- finance XP (no-spend days) ----------

    @Test
    void completedNoSpendDayEarnsThirtyXp() {
        // The month is actively tracked: an expense exists on another day.
        givenExpenseDay(USER, TODAY.minusDays(3));
        givenSleep(USER, TODAY.minusDays(1), 460);

        DailyRing yesterday = ringsService.computeDay(TODAY.minusDays(1));

        assertEquals(30, yesterday.getFinanceXp());
        assertEquals(20 + 30, yesterday.getXpEarned()); // rest ring + no-spend
    }

    @Test
    void todayNeverEarnsNoSpendXpWhileInProgress() {
        givenExpenseDay(USER, TODAY.minusDays(3));
        givenSleep(USER, TODAY, 460);

        DailyRing ring = ringsService.computeDay(TODAY);

        assertEquals(0, ring.getFinanceXp()); // the day has not rolled over yet
    }

    @Test
    void aDayWithExpensesEarnsNoFinanceXp() {
        givenExpenseDay(USER, TODAY.minusDays(1));
        givenSleep(USER, TODAY.minusDays(1), 460);

        DailyRing ring = ringsService.computeDay(TODAY.minusDays(1));

        assertEquals(0, ring.getFinanceXp());
    }

    @Test
    void anUntrackedMonthEarnsNoFinanceXp() {
        // No financial logs at all — absence of data is not restraint.
        givenSleep(USER, TODAY.minusDays(1), 460);

        DailyRing ring = ringsService.computeDay(TODAY.minusDays(1));

        assertEquals(0, ring.getFinanceXp());
    }

    // ---------- rollover ----------

    @Test
    void beforeRolloverHourTheRingDayIsStillYesterday() {
        setNow(TODAY, 2); // 02:00 — still ring day July 18
        assertEquals(TODAY.minusDays(1), ringsService.ringDayToday());

        setNow(TODAY, 4); // 04:00 — July 19 begins
        assertEquals(TODAY, ringsService.ringDayToday());
    }

    @Test
    void focusSessionEndingAtTwoAmCountsTowardThePreviousDay() {
        // Real FocusSessionService against a mocked repository: a session run
        // 01:15 → 02:00 on July 19 must land in July 18's ring day.
        FocusSessionRepository focusRepository = org.mockito.Mockito.mock(FocusSessionRepository.class);
        FocusSessionService realService = new FocusSessionService(focusRepository);
        FocusSession lateSession = FocusSession.builder()
                .userId(USER)
                .durationMinutes(45)
                .status(FocusSessionStatus.COMPLETED)
                .startTime(atZone(TODAY, 1, 15))
                .build();
        when(focusRepository.findByUserIdAndStatusAndStartTimeBetween(any(), any(), any(), any()))
                .thenReturn(List.of(lateSession));

        Map<LocalDate, Long> byDay = realService.getRingDayMinutes(USER, TODAY.minusDays(1), TODAY, 4);

        assertEquals(45L, byDay.get(TODAY.minusDays(1)));
        assertNull(byDay.get(TODAY));
    }

    // ---------- streak replay ----------

    @Test
    void unbrokenPerfectRunCountsEveryDayIncludingToday() {
        seedPerfectDays(USER, TODAY.minusDays(4), TODAY);

        StreakState state = ringsService.recomputeStreak();

        assertEquals(5, state.getCurrentStreak());
        assertEquals(5, state.getLongestStreak());
        assertEquals(TODAY, state.getLastPerfectDate());
        assertEquals(5, state.getPerfectDaysAllTime());
        assertEquals(500, state.getTotalXp()); // 5 perfect days, no milestone yet
        assertEquals(3, state.getLevel());     // floor(sqrt(5)) + 1
    }

    @Test
    void todayStillInProgressNeverBreaksTheStreak() {
        seedPerfectDays(USER, TODAY.minusDays(3), TODAY.minusDays(1));
        seedDay(USER, TODAY, 1, false); // today: one ring so far

        StreakState state = ringsService.recomputeStreak();

        assertEquals(3, state.getCurrentStreak());
        // No freeze spent on a day that has not rolled over yet.
        assertEquals(2, state.getFreezesAvailable());
        assertFalse(ringsFor(USER).get(TODAY).isFrozen());
    }

    @Test
    void freezeCoversOnePastBadDayAndHoldsTheChain() {
        seedPerfectDays(USER, TODAY.minusDays(8), TODAY.minusDays(4));
        seedDay(USER, TODAY.minusDays(3), 1, false); // the bad Tuesday
        seedPerfectDays(USER, TODAY.minusDays(2), TODAY);

        StreakState state = ringsService.recomputeStreak();

        // 5 + (frozen, held not extended) + 2 + today = 8
        assertEquals(8, state.getCurrentStreak());
        assertEquals(1, state.getFreezesAvailable());
        assertEquals(1, state.getFreezesUsedThisMonth());
        DailyRing frozen = ringsFor(USER).get(TODAY.minusDays(3));
        assertTrue(frozen.isFrozen());
        assertEquals(0, frozen.getXpEarned()); // frozen day earns nothing
    }

    @Test
    void aGapDayWithNoDocumentGetsAFrozenDocumentWhenCovered() {
        seedPerfectDays(USER, TODAY.minusDays(5), TODAY.minusDays(3));
        // minusDays(2): nothing logged at all — no document.
        seedPerfectDays(USER, TODAY.minusDays(1), TODAY);

        StreakState state = ringsService.recomputeStreak();

        assertEquals(5, state.getCurrentStreak());
        DailyRing gap = ringsFor(USER).get(TODAY.minusDays(2));
        assertNotNull(gap);
        assertTrue(gap.isFrozen());
        assertEquals(0, gap.getRingsClosed());
    }

    @Test
    void freezeExhaustionResetsTheStreak() {
        seedPerfectDays(USER, TODAY.minusDays(9), TODAY.minusDays(6));
        seedDay(USER, TODAY.minusDays(5), 0, false);
        seedDay(USER, TODAY.minusDays(4), 0, false);
        seedDay(USER, TODAY.minusDays(3), 0, false); // third bad day — no freeze left
        seedPerfectDays(USER, TODAY.minusDays(2), TODAY);

        StreakState state = ringsService.recomputeStreak();

        assertEquals(3, state.getCurrentStreak());
        assertEquals(0, state.getFreezesAvailable());
        assertTrue(ringsFor(USER).get(TODAY.minusDays(5)).isFrozen());
        assertTrue(ringsFor(USER).get(TODAY.minusDays(4)).isFrozen());
        assertFalse(ringsFor(USER).get(TODAY.minusDays(3)).isFrozen());
        // Longest run: the 4 perfect days before the break (frozen days hold, not extend).
        assertEquals(4, state.getLongestStreak());
    }

    @Test
    void monthBoundaryRestoresFreezes() {
        // June: two freezes consumed. July: a fresh pair, one consumed.
        seedPerfectDays(USER, LocalDate.of(2026, 6, 25), LocalDate.of(2026, 6, 28));
        seedDay(USER, LocalDate.of(2026, 6, 29), 1, false);
        seedDay(USER, LocalDate.of(2026, 6, 30), 1, false);
        seedDay(USER, LocalDate.of(2026, 7, 1), 1, false);
        seedPerfectDays(USER, LocalDate.of(2026, 7, 2), TODAY);

        StreakState state = ringsService.recomputeStreak();

        // 4 (June) + 18 (Jul 2–19); the three frozen days hold but do not extend.
        assertEquals(22, state.getCurrentStreak());
        assertTrue(ringsFor(USER).get(LocalDate.of(2026, 6, 29)).isFrozen());
        assertTrue(ringsFor(USER).get(LocalDate.of(2026, 6, 30)).isFrozen());
        assertTrue(ringsFor(USER).get(LocalDate.of(2026, 7, 1)).isFrozen());
        assertEquals(1, state.getFreezesAvailable());
        assertEquals(1, state.getFreezesUsedThisMonth());
        assertEquals("2026-07", state.getFreezeMonthKey());
    }

    @Test
    void milestoneXpIsAwardedOnceAndSurvivesReplays() {
        seedPerfectDays(USER, TODAY.minusDays(6), TODAY); // exactly 7

        StreakState first = ringsService.recomputeStreak();
        StreakState second = ringsService.recomputeStreak();

        assertEquals(7, first.getCurrentStreak());
        assertEquals(700 + 100, first.getTotalXp()); // 7 perfect days + 7-day milestone
        assertEquals(first.getTotalXp(), second.getTotalXp());
    }

    @Test
    void replayIsSelfHealingWhenABadDayIsBackfilled() {
        seedPerfectDays(USER, TODAY.minusDays(4), TODAY.minusDays(2));
        seedDay(USER, TODAY.minusDays(1), 2, false);
        seedPerfectDays(USER, TODAY, TODAY);
        ringsService.recomputeStreak();
        assertTrue(ringsFor(USER).get(TODAY.minusDays(1)).isFrozen());

        // The user backfills sleep and the day recomputes to perfect.
        seedDay(USER, TODAY.minusDays(1), 3, true);
        StreakState healed = ringsService.recomputeStreak();

        assertFalse(ringsFor(USER).get(TODAY.minusDays(1)).isFrozen());
        assertEquals(5, healed.getCurrentStreak());
        assertEquals(2, healed.getFreezesAvailable()); // the freeze came back
    }

    @Test
    void emptyHistoryYieldsAZeroedState() {
        StreakState state = ringsService.recomputeStreak();

        assertEquals(0, state.getCurrentStreak());
        assertEquals(0, state.getTotalXp());
        assertEquals(1, state.getLevel());
        assertEquals(2, state.getFreezesAvailable());
    }

    // ---------- isolation ----------

    @Test
    void ringsAndStreaksNeverLeakAcrossUsers() {
        seedPerfectDays(USER, TODAY.minusDays(2), TODAY);
        ringsService.recomputeStreak();
        assertEquals(3, streakStore.get(USER).getCurrentStreak());

        UserContext.setUserId(OTHER_USER);
        givenSleep(OTHER_USER, TODAY, 480);
        ringsService.computeDay(TODAY);
        StreakState other = ringsService.recomputeStreak();

        assertEquals(0, other.getCurrentStreak()); // one ring today ≠ qualified
        assertEquals(OTHER_USER, other.getUserId());
        // User A's world is untouched.
        assertEquals(3, streakStore.get(USER).getCurrentStreak());
        assertEquals(3, ringsFor(USER).size());
        assertEquals(1, ringsFor(OTHER_USER).size());
        assertTrue(ringsFor(OTHER_USER).values().stream().allMatch(r -> OTHER_USER.equals(r.getUserId())));
    }

    // ---------- level math ----------

    @Test
    void levelCurveMatchesTheDocumentedFormula() {
        assertEquals(1, RingsService.levelFor(0));
        assertEquals(1, RingsService.levelFor(99));
        assertEquals(2, RingsService.levelFor(100));
        assertEquals(2, RingsService.levelFor(399));
        assertEquals(3, RingsService.levelFor(400));
        assertEquals(0, RingsService.levelFloorXp(1));
        assertEquals(100, RingsService.levelFloorXp(2));
        assertEquals(400, RingsService.levelFloorXp(3));
    }

    // ---------- helpers ----------

    private Map<LocalDate, DailyRing> ringsFor(String userId) {
        return ringStore.computeIfAbsent(userId, k -> new HashMap<>());
    }

    private void setNow(LocalDate date, int hour) {
        Instant now = atZone(date, hour, 0);
        ringsService.setClock(Clock.fixed(now, ZONE));
    }

    private Instant atZone(LocalDate date, int hour, int minute) {
        return LocalDateTime.of(date, java.time.LocalTime.of(hour, minute)).atZone(ZONE).toInstant();
    }

    private void givenSleep(String userId, LocalDate date, int minutes) {
        sleepStore.computeIfAbsent(userId, k -> new HashMap<>())
                .put(date, SleepLog.builder().userId(userId).date(date).durationMinutes(minutes).build());
    }

    private void givenFocus(String userId, LocalDate date, long minutes) {
        focusStore.computeIfAbsent(userId, k -> new HashMap<>()).put(date, minutes);
    }

    /** Seed a financial log with one expense transaction on the date. */
    private void givenExpenseDay(String userId, LocalDate date) {
        DailyFinancialLog log = DailyFinancialLog.builder()
                .userId(userId)
                .dateString(date.toString())
                .transactions(new java.util.LinkedHashMap<>(Map.of(
                        "Food", List.of(FinancialTransaction.builder()
                                .description("lunch").amount(new java.math.BigDecimal("120")).type("Expense").build()))))
                .build();
        finStore.computeIfAbsent(userId, k -> new HashMap<>()).put(date.toString(), log);
    }

    /** Seed a food log: {@code proteinGoal}, then one protein value per meal. */
    private void givenMeals(String userId, LocalDate date, int proteinGoal, int mealCount, int... proteinPerMeal) {
        List<MealEntry> meals = new ArrayList<>();
        for (int i = 0; i < mealCount; i++) {
            meals.add(MealEntry.builder()
                    .description("meal-" + i)
                    .proteinGrams(i < proteinPerMeal.length ? proteinPerMeal[i] : 0)
                    .build());
        }
        DailyFoodLog log = DailyFoodLog.builder()
                .userId(userId)
                .dateString(date.toString())
                .date(date)
                .proteinGoal(proteinGoal > 0 ? proteinGoal : null)
                .meals(new java.util.LinkedHashMap<>(Map.of("Meals", meals)))
                .build();
        foodStore.computeIfAbsent(userId, k -> new HashMap<>()).put(date.toString(), log);
    }

    private void givenStrava(String userId, LocalDate date, double minutes) {
        stravaStore.computeIfAbsent(userId, k -> new HashMap<>())
                .computeIfAbsent(date, k -> new ArrayList<>())
                .add(StravaActivity.builder().userId(userId).date(date).movingTimeMinutes(minutes).build());
    }

    /** Seed an already-computed ring day directly into the fake store. */
    private void seedDay(String userId, LocalDate date, int ringsClosed, boolean perfect) {
        DailyRing existing = ringsFor(userId).get(date);
        DailyRing ring = existing != null ? existing : DailyRing.builder()
                .id("seed-" + idSequence.incrementAndGet())
                .userId(userId)
                .date(date)
                .build();
        ring.setRingsClosed(ringsClosed);
        ring.setPerfect(perfect);
        ring.setFrozen(false);
        ring.setXpEarned(ringsClosed * 20 + (perfect ? 40 : 0));
        ring.setComputedAt(Instant.now());
        ringsFor(userId).put(date, ring);
    }

    private void seedPerfectDays(String userId, LocalDate from, LocalDate to) {
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            seedDay(userId, d, 3, true);
        }
    }
}
