package com.personal_dashboard.backend.service;

import com.personal_dashboard.backend.dto.GoalBoardResponse;
import com.personal_dashboard.backend.dto.GoalBoardResponse.GoalProgressView;
import com.personal_dashboard.backend.dto.GoalJourneyResponse;
import com.personal_dashboard.backend.dto.request.GoalCheckInRequest;
import com.personal_dashboard.backend.dto.request.GoalRequest;
import com.personal_dashboard.backend.model.Goal;
import com.personal_dashboard.backend.model.GoalCamp;
import com.personal_dashboard.backend.model.GoalCheckIn;
import com.personal_dashboard.backend.repository.GoalCheckInRepository;
import com.personal_dashboard.backend.repository.GoalRepository;
import com.personal_dashboard.backend.security.UserContext;
import com.personal_dashboard.backend.util.GoalJourney;
import com.personal_dashboard.backend.util.GoalProgress;
import com.personal_dashboard.backend.util.ParallelReads;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Goals and their check-ins. Stores only rules and raw progress; every verdict the board
 * shows comes from {@link GoalProgress}, evaluated on read.
 *
 * <p>Mutations return the goal's freshly judged {@link GoalProgressView} so the client can
 * swap one row in place instead of re-reading the whole board.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class GoalService {

    /** A board you can hold in your head; more than this and none of them get attention. */
    public static final int MAX_ACTIVE_GOALS = 8;

    private static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");

    private final GoalRepository goalRepository;
    private final GoalCheckInRepository checkInRepository;
    private final GoalCampService campService;
    private final GoalKitService kitService;

    // ── Reads ────────────────────────────────────────────────────────────

    public GoalBoardResponse getBoard(LocalDate requestedToday) {
        String userId = UserContext.getRequiredUserId();
        LocalDate today = resolveToday(requestedToday);

        CompletableFuture<List<Goal>> goalsRead = ParallelReads.fork(
                () -> goalRepository.findByUserIdAndStatusOrderByOrderAsc(userId, Goal.STATUS_ACTIVE));
        CompletableFuture<List<GoalCheckIn>> checkInsRead = ParallelReads.fork(
                () -> checkInRepository.findByUserId(userId));
        CompletableFuture<GoalCamp> campRead = ParallelReads.fork(() -> campService.readCampDocument(userId));
        List<Goal> goals = ParallelReads.join(goalsRead);
        Map<String, List<GoalCheckIn>> byGoal = ParallelReads.join(checkInsRead).stream()
                .collect(Collectors.groupingBy(GoalCheckIn::getGoalId));

        List<GoalProgressView> views = goals.stream()
                .map(g -> GoalProgress.evaluate(g, byGoal.getOrDefault(g.getId(), List.of()), today))
                .toList();

        return GoalBoardResponse.builder()
                .date(today.toString())
                .weekStart(GoalProgress.weekStart(today).toString())
                .goals(views)
                .camp(campService.build(ParallelReads.join(campRead), goals, byGoal, views, today))
                .build();
    }

    /** Active and archived goals, for managing the list. */
    public List<Goal> listGoals() {
        return goalRepository.findByUserIdOrderByOrderAsc(UserContext.getRequiredUserId());
    }

    // ── Goals ────────────────────────────────────────────────────────────

    public GoalProgressView createGoal(GoalRequest request, LocalDate requestedToday) {
        String userId = UserContext.getRequiredUserId();
        LocalDate today = resolveToday(requestedToday);
        if (goalRepository.countByUserIdAndStatus(userId, Goal.STATUS_ACTIVE) >= MAX_ACTIVE_GOALS) {
            throw new IllegalArgumentException("You already have " + MAX_ACTIVE_GOALS
                    + " active goals — archive one to make room.");
        }
        int nextOrder = goalRepository.findByUserIdOrderByOrderAsc(userId).stream()
                .mapToInt(Goal::getOrder)
                .max()
                .orElse(-1) + 1;

        Goal goal = Goal.builder()
                .userId(userId)
                .status(Goal.STATUS_ACTIVE)
                .order(nextOrder)
                .build();
        applyRequest(goal, request, today);
        Goal saved = goalRepository.save(goal);
        log.info("Created goal {} ({} {} {})", saved.getId(), saved.getMeasure(), saved.getPeriod(), saved.getTarget());
        return GoalProgress.evaluate(saved, List.of(), today);
    }

    public GoalProgressView updateGoal(String id, GoalRequest request, LocalDate requestedToday) {
        LocalDate today = resolveToday(requestedToday);
        Goal goal = requireGoal(id);
        applyRequest(goal, request, today);
        Goal saved = goalRepository.save(goal);
        log.info("Updated goal {}", id);
        return evaluate(saved, today);
    }

    public Goal setStatus(String id, String status) {
        String userId = UserContext.getRequiredUserId();
        Goal goal = requireGoal(id);
        if (Goal.STATUS_ACTIVE.equals(status) && !Goal.STATUS_ACTIVE.equals(goal.getStatus())
                && goalRepository.countByUserIdAndStatus(userId, Goal.STATUS_ACTIVE) >= MAX_ACTIVE_GOALS) {
            throw new IllegalArgumentException("You already have " + MAX_ACTIVE_GOALS
                    + " active goals — archive one to bring this back.");
        }
        goal.setStatus(status);
        log.info("Goal {} → {}", id, status);
        return goalRepository.save(goal);
    }

    /** Removes the goal and its whole history. Archiving is the reversible option. */
    public void deleteGoal(String id) {
        String userId = UserContext.getRequiredUserId();
        Goal goal = requireGoal(id);
        checkInRepository.deleteByUserIdAndGoalId(userId, goal.getId());
        kitService.deleteFor(userId, goal.getId());
        goalRepository.delete(goal);
        log.info("Deleted goal {}, its check-ins and its kit", id);
    }

    // ── Check-ins ────────────────────────────────────────────────────────

    public GoalProgressView addCheckIn(String goalId, GoalCheckInRequest request, LocalDate requestedToday) {
        String userId = UserContext.getRequiredUserId();
        LocalDate today = resolveToday(requestedToday);
        Goal goal = requireGoal(goalId);
        if (!Goal.STATUS_ACTIVE.equals(goal.getStatus())) {
            throw new IllegalArgumentException("This goal is archived — bring it back to log progress.");
        }
        LocalDate date = LocalDate.parse(request.getDate());
        // One day of slack either way: the client's local day can run ahead of the server's.
        if (date.isAfter(serverToday().plusDays(1))) {
            throw new IllegalArgumentException("Progress can't be logged for a future day.");
        }

        if (goal.isCount()) {
            if (request.getValue() == null) {
                throw new IllegalArgumentException("How much? A value is required for this goal.");
            }
        } else if (checkInRepository.findByUserIdAndGoalIdAndDate(userId, goalId, date).stream()
                .anyMatch(c -> java.util.Objects.equals(c.getPractice(), blankToNull(request.getPractice()))
                        && java.util.Objects.equals(c.getSession(), blankToNull(request.getSession())))) {
            // A check goal is done or not — marking the same thing twice changes nothing. A
            // different practice or session on the same day is its own entry (the day is hit
            // either way).
            return evaluate(goal, today);
        }

        // Logging before the goal existed means you were already doing it: move the start back.
        if (goal.getStartDate() != null && date.isBefore(goal.getStartDate())) {
            goal.setStartDate(date);
            goal = goalRepository.save(goal);
        }

        checkInRepository.save(GoalCheckIn.builder()
                .userId(userId)
                .goalId(goalId)
                .date(date)
                .value(goal.isCount() ? request.getValue() : 1)
                .note(blankToNull(request.getNote()))
                .practice(blankToNull(request.getPractice()))
                .session(blankToNull(request.getSession()))
                .minutes(request.getMinutes())
                .source("manual")
                .build());
        return evaluate(goal, today);
    }

    /**
     * Every day this goal was tended, oldest first — what a goal's own world draws (The
     * Quiet Path lays one stone per day). Only days with progress appear: a quiet day is
     * never a row, so nothing can be drawn as missing.
     */
    public GoalJourneyResponse getJourney(String goalId, LocalDate requestedToday) {
        String userId = UserContext.getRequiredUserId();
        LocalDate today = resolveToday(requestedToday);
        Goal goal = requireGoal(goalId);
        List<GoalCheckIn> checkIns = checkInRepository.findByUserIdAndGoalId(userId, goal.getId());
        return GoalJourneyResponse.builder()
                .goalId(goal.getId())
                .date(today.toString())
                .days(GoalJourney.days(checkIns, today))
                .kit(kitService.pages(userId, goal.getId()))
                .build();
    }

    public GoalProgressView deleteCheckIn(String goalId, String checkInId, LocalDate requestedToday) {
        String userId = UserContext.getRequiredUserId();
        LocalDate today = resolveToday(requestedToday);
        Goal goal = requireGoal(goalId);
        GoalCheckIn checkIn = checkInRepository.findByIdAndUserIdAndGoalId(checkInId, userId, goalId)
                .orElseThrow(() -> new IllegalArgumentException("Check-in not found with id: " + checkInId));
        checkInRepository.delete(checkIn);
        return evaluate(goal, today);
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    /**
     * Normalises the four shapes the UI offers: CHECK + DAY is always 1 a day, CHECK + WEEK
     * counts days so it is whole and at most 7, and only DAY goals carry daysPerWeek.
     */
    private void applyRequest(Goal goal, GoalRequest request, LocalDate today) {
        String measure = request.getMeasure();
        String period = request.getPeriod();
        double target = request.getTarget();

        if (Goal.MEASURE_CHECK.equals(measure)) {
            if (Goal.PERIOD_DAY.equals(period)) {
                target = 1;
            } else {
                target = Math.round(target);
                if (target < 1 || target > 7) {
                    throw new IllegalArgumentException("A times-a-week goal needs between 1 and 7 days.");
                }
            }
        }

        LocalDate start = request.getStartDate() != null ? LocalDate.parse(request.getStartDate())
                : goal.getStartDate() != null ? goal.getStartDate() : today;
        if (start.isAfter(today)) {
            throw new IllegalArgumentException("A goal can't start in the future.");
        }

        goal.setTitle(request.getTitle().trim());
        goal.setIcon(blankToNull(request.getIcon()));
        goal.setColor(blankToNull(request.getColor()));
        goal.setWorld(blankToNull(request.getWorld()));
        goal.setMeasure(measure);
        goal.setPeriod(period);
        goal.setTarget(target);
        goal.setUnit(Goal.MEASURE_COUNT.equals(measure) ? blankToNull(request.getUnit()) : null);
        goal.setDaysPerWeek(Goal.PERIOD_DAY.equals(period)
                ? (request.getDaysPerWeek() == null ? 7 : request.getDaysPerWeek())
                : null);
        goal.setStartDate(start);
    }

    private GoalProgressView evaluate(Goal goal, LocalDate today) {
        List<GoalCheckIn> checkIns = checkInRepository.findByUserIdAndGoalId(goal.getUserId(), goal.getId());
        return GoalProgress.evaluate(goal, checkIns, today);
    }

    private Goal requireGoal(String id) {
        String userId = UserContext.getRequiredUserId();
        return goalRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new IllegalArgumentException("Goal not found with id: " + id));
    }

    /** The client's local day when given (it knows about the 04:00 rollover), else the server's. */
    private LocalDate resolveToday(LocalDate requested) {
        return requested != null ? requested : serverToday();
    }

    LocalDate serverToday() {
        return LocalDate.now(ZONE);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
