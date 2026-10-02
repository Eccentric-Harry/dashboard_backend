package com.personal_dashboard.backend.service;

import com.personal_dashboard.backend.dto.GoalBoardResponse;
import com.personal_dashboard.backend.dto.GoalBoardResponse.GoalProgressView;
import com.personal_dashboard.backend.dto.request.GoalCheckInRequest;
import com.personal_dashboard.backend.dto.request.GoalRequest;
import com.personal_dashboard.backend.model.Goal;
import com.personal_dashboard.backend.model.GoalCheckIn;
import com.personal_dashboard.backend.repository.GoalCheckInRepository;
import com.personal_dashboard.backend.repository.GoalRepository;
import com.personal_dashboard.backend.security.UserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GoalServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 1);

    @Mock
    private GoalRepository goalRepository;

    @Mock
    private GoalCheckInRepository checkInRepository;

    @Mock
    private GoalCampService campService;

    @Mock
    private GoalKitService kitService;

    @InjectMocks
    private GoalService goalService;

    @BeforeEach
    void setUp() {
        UserContext.setUserId("test-user");
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private static GoalRequest request(String measure, String period, double target) {
        return GoalRequest.builder().title("  Read  ").measure(measure).period(period).target(target)
                .unit("pages").daysPerWeek(5).color("mint").build();
    }

    private static Goal goal(String measure, String period, double target) {
        return Goal.builder().id("g1").userId("test-user").title("Read").measure(measure).period(period)
                .target(target).daysPerWeek(Goal.PERIOD_DAY.equals(period) ? 5 : null)
                .status(Goal.STATUS_ACTIVE).startDate(TODAY.minusDays(20)).build();
    }

    @Test
    void createRefusesPastTheActiveCap() {
        when(goalRepository.countByUserIdAndStatus("test-user", Goal.STATUS_ACTIVE)).thenReturn((long) GoalService.MAX_ACTIVE_GOALS);
        assertThrows(IllegalArgumentException.class,
                () -> goalService.createGoal(request("COUNT", "DAY", 10), TODAY));
        verify(goalRepository, never()).save(any());
    }

    @Test
    void createNormalisesTheShapeAndAppendsToTheBoard() {
        when(goalRepository.findByUserIdOrderByOrderAsc("test-user"))
                .thenReturn(List.of(Goal.builder().order(0).build(), Goal.builder().order(3).build()));
        when(goalRepository.save(any(Goal.class))).thenAnswer(inv -> inv.getArgument(0));

        GoalProgressView v = goalService.createGoal(request("CHECK", "DAY", 12), TODAY);

        Goal saved = v.getGoal();
        assertEquals("Read", saved.getTitle());
        assertEquals("mint", saved.getColor());
        assertEquals(1, saved.getTarget(), "a daily check is always once a day");
        assertNull(saved.getUnit(), "only COUNT goals carry a unit");
        assertEquals(5, saved.getDaysPerWeek());
        assertEquals(4, saved.getOrder());
        assertEquals(TODAY, saved.getStartDate());
        assertEquals("test-user", saved.getUserId());
    }

    @Test
    void timesPerWeekMustFitInAWeek() {
        assertThrows(IllegalArgumentException.class,
                () -> goalService.createGoal(request("CHECK", "WEEK", 9), TODAY));
    }

    @Test
    void aGoalCannotStartInTheFuture() {
        GoalRequest req = request("COUNT", "DAY", 10);
        req.setStartDate(TODAY.plusDays(2).toString());
        assertThrows(IllegalArgumentException.class, () -> goalService.createGoal(req, TODAY));
    }

    @Test
    void checkingADailyCheckTwiceChangesNothing() {
        Goal g = goal("CHECK", "DAY", 1);
        when(goalRepository.findByIdAndUserId("g1", "test-user")).thenReturn(Optional.of(g));
        when(checkInRepository.findByUserIdAndGoalIdAndDate("test-user", "g1", TODAY))
                .thenReturn(List.of(GoalCheckIn.builder().goalId("g1").date(TODAY).value(1).build()));
        when(checkInRepository.findByUserIdAndGoalId("test-user", "g1"))
                .thenReturn(List.of(GoalCheckIn.builder().goalId("g1").date(TODAY).value(1).build()));

        GoalProgressView v = goalService.addCheckIn("g1",
                GoalCheckInRequest.builder().date(TODAY.toString()).build(), TODAY);

        assertTrue(v.getToday().isHit());
        verify(checkInRepository, never()).save(any());
    }

    @Test
    void aCheckGoalTakesEachPracticeOnceADay() {
        Goal check = goal("CHECK", "DAY", 1);
        when(goalRepository.findByIdAndUserId("g1", "test-user")).thenReturn(Optional.of(check));
        when(checkInRepository.findByUserIdAndGoalIdAndDate("test-user", "g1", TODAY))
                .thenReturn(List.of(GoalCheckIn.builder().goalId("g1").date(TODAY).value(1).practice("breathe").build()));

        // The same practice again changes nothing…
        goalService.addCheckIn("g1", GoalCheckInRequest.builder().date(TODAY.toString()).practice("breathe").build(), TODAY);
        verify(checkInRepository, never()).save(any());

        // …a guided session of the same practice is its own entry too…
        goalService.addCheckIn("g1", GoalCheckInRequest.builder().date(TODAY.toString()).practice("breathe").session("long-exhale").minutes(3).build(), TODAY);
        ArgumentCaptor<GoalCheckIn> session = ArgumentCaptor.forClass(GoalCheckIn.class);
        verify(checkInRepository).save(session.capture());
        assertEquals("long-exhale", session.getValue().getSession());
        assertEquals(3, session.getValue().getMinutes());
        clearInvocations(checkInRepository);
        when(checkInRepository.findByUserIdAndGoalIdAndDate("test-user", "g1", TODAY))
                .thenReturn(List.of(GoalCheckIn.builder().goalId("g1").date(TODAY).value(1).practice("breathe").build()));

        // …and so is a different practice.
        goalService.addCheckIn("g1", GoalCheckInRequest.builder().date(TODAY.toString()).practice("walk").build(), TODAY);
        ArgumentCaptor<GoalCheckIn> saved = ArgumentCaptor.forClass(GoalCheckIn.class);
        verify(checkInRepository).save(saved.capture());
        assertEquals("walk", saved.getValue().getPractice());
        assertEquals(1, saved.getValue().getValue());
    }

    @Test
    void aGoalKeepsTheWorldItWasGiven() {
        when(goalRepository.findByUserIdOrderByOrderAsc("test-user")).thenReturn(List.of());
        when(goalRepository.save(any(Goal.class))).thenAnswer(inv -> inv.getArgument(0));
        GoalRequest req = request("CHECK", "DAY", 1);
        req.setWorld("path");
        assertEquals("path", goalService.createGoal(req, TODAY).getGoal().getWorld());
        req.setWorld(" ");
        assertNull(goalService.createGoal(req, TODAY).getGoal().getWorld(), "blank means camp only");
    }

    @Test
    void countGoalsNeedAValueAndCheckGoalsRecordOne() {
        Goal count = goal("COUNT", "DAY", 10);
        when(goalRepository.findByIdAndUserId("g1", "test-user")).thenReturn(Optional.of(count));
        assertThrows(IllegalArgumentException.class, () -> goalService.addCheckIn("g1",
                GoalCheckInRequest.builder().date(TODAY.toString()).build(), TODAY));

        Goal check = goal("CHECK", "WEEK", 3);
        when(goalRepository.findByIdAndUserId("g1", "test-user")).thenReturn(Optional.of(check));
        when(checkInRepository.findByUserIdAndGoalIdAndDate("test-user", "g1", TODAY)).thenReturn(List.of());
        goalService.addCheckIn("g1", GoalCheckInRequest.builder().date(TODAY.toString()).value(40.0).note(" ").build(), TODAY);

        ArgumentCaptor<GoalCheckIn> saved = ArgumentCaptor.forClass(GoalCheckIn.class);
        verify(checkInRepository).save(saved.capture());
        assertEquals(1, saved.getValue().getValue());
        assertNull(saved.getValue().getNote());
        assertEquals("manual", saved.getValue().getSource());
    }

    @Test
    void loggingBeforeTheStartMovesTheStartBack() {
        Goal g = goal("COUNT", "DAY", 10);
        g.setStartDate(TODAY);
        when(goalRepository.findByIdAndUserId("g1", "test-user")).thenReturn(Optional.of(g));
        when(goalRepository.save(any(Goal.class))).thenAnswer(inv -> inv.getArgument(0));

        LocalDate yesterday = TODAY.minusDays(1);
        goalService.addCheckIn("g1", GoalCheckInRequest.builder().date(yesterday.toString()).value(12.0).build(), TODAY);

        assertEquals(yesterday, g.getStartDate());
    }

    @Test
    void futureDaysAndArchivedGoalsAreRefused() {
        Goal g = goal("COUNT", "DAY", 10);
        when(goalRepository.findByIdAndUserId("g1", "test-user")).thenReturn(Optional.of(g));
        String farFuture = LocalDate.now().plusDays(5).toString();
        assertThrows(IllegalArgumentException.class, () -> goalService.addCheckIn("g1",
                GoalCheckInRequest.builder().date(farFuture).value(5.0).build(), TODAY));

        g.setStatus(Goal.STATUS_ARCHIVED);
        assertThrows(IllegalArgumentException.class, () -> goalService.addCheckIn("g1",
                GoalCheckInRequest.builder().date(TODAY.toString()).value(5.0).build(), TODAY));
        verify(checkInRepository, never()).save(any());
    }

    @Test
    void undoingAnotherGoalsCheckInIsNotFound() {
        when(goalRepository.findByIdAndUserId("g1", "test-user")).thenReturn(Optional.of(goal("COUNT", "DAY", 10)));
        when(checkInRepository.findByIdAndUserIdAndGoalId("c9", "test-user", "g1")).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> goalService.deleteCheckIn("g1", "c9", TODAY));
        verify(checkInRepository, never()).delete(any());
    }

    @Test
    void restoringPastTheCapIsRefused() {
        Goal g = goal("COUNT", "DAY", 10);
        g.setStatus(Goal.STATUS_ARCHIVED);
        when(goalRepository.findByIdAndUserId("g1", "test-user")).thenReturn(Optional.of(g));
        when(goalRepository.countByUserIdAndStatus("test-user", Goal.STATUS_ACTIVE)).thenReturn((long) GoalService.MAX_ACTIVE_GOALS);
        assertThrows(IllegalArgumentException.class, () -> goalService.setStatus("g1", Goal.STATUS_ACTIVE));
    }

    @Test
    void deleteRemovesTheHistoryWithTheGoal() {
        Goal g = goal("COUNT", "DAY", 10);
        when(goalRepository.findByIdAndUserId("g1", "test-user")).thenReturn(Optional.of(g));
        goalService.deleteGoal("g1");
        verify(checkInRepository).deleteByUserIdAndGoalId("test-user", "g1");
        verify(kitService).deleteFor("test-user", "g1");
        verify(goalRepository).delete(g);
    }

    @Test
    void theJourneyCarriesTheWorldsKit() {
        Goal g = goal("CHECK", "DAY", 1);
        when(goalRepository.findByIdAndUserId("g1", "test-user")).thenReturn(Optional.of(g));
        when(checkInRepository.findByUserIdAndGoalId("test-user", "g1")).thenReturn(List.of());
        var page = com.personal_dashboard.backend.model.GoalKit.Page.builder().picks(List.of("Tight shoulders")).build();
        when(kitService.pages("test-user", "g1")).thenReturn(java.util.Map.of("signs", page));
        var journey = goalService.getJourney("g1", TODAY);
        assertEquals(List.of("Tight shoulders"), journey.getKit().get("signs").getPicks());
    }

    @Test
    void boardJudgesEachActiveGoalAgainstItsOwnCheckIns() {
        Goal a = goal("COUNT", "DAY", 10);
        Goal b = goal("CHECK", "WEEK", 3).toBuilder().id("g2").build();
        when(goalRepository.findByUserIdAndStatusOrderByOrderAsc("test-user", Goal.STATUS_ACTIVE)).thenReturn(List.of(a, b));
        when(checkInRepository.findByUserId("test-user")).thenReturn(List.of(
                GoalCheckIn.builder().goalId("g1").date(TODAY).value(10).build(),
                GoalCheckIn.builder().goalId("g2").date(TODAY.minusDays(1)).value(1).build()));

        GoalBoardResponse board = goalService.getBoard(TODAY);

        assertEquals("2026-10-01", board.getDate());
        assertEquals("2026-09-28", board.getWeekStart());
        assertEquals(2, board.getGoals().size());
        assertTrue(board.getGoals().get(0).getToday().isHit());
        assertFalse(board.getGoals().get(1).getToday().isHit());
        assertEquals(1, board.getGoals().get(1).getWeek().getValue());
    }
}
