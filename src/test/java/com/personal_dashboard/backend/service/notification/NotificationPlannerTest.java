package com.personal_dashboard.backend.service.notification;

import com.personal_dashboard.backend.model.DailyTask;
import com.personal_dashboard.backend.model.NotificationStatus;
import com.personal_dashboard.backend.model.PushSubscription;
import com.personal_dashboard.backend.model.ScheduledNotification;
import com.personal_dashboard.backend.model.UserAccount;
import com.personal_dashboard.backend.repository.DailyTaskRepository;
import com.personal_dashboard.backend.repository.PushSubscriptionRepository;
import com.personal_dashboard.backend.repository.ScheduledNotificationRepository;
import com.personal_dashboard.backend.repository.UserAccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Planning is where "when does this fire" is decided, so these tests are mostly about
 * time: the past is never planned (the bug that made reloading the app fire alerts), wall
 * clock is resolved in the right zone, and replanning is idempotent.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class NotificationPlannerTest {

    private static final String USER = "user-1";
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    @Mock private DailyTaskRepository dailyTaskRepository;
    @Mock private PushSubscriptionRepository pushSubscriptionRepository;
    @Mock private ScheduledNotificationRepository notificationRepository;
    @Mock private UserAccountRepository userAccountRepository;

    @InjectMocks private NotificationPlanner planner;

    private PushSubscription device;

    @BeforeEach
    void setUp() {
        planner.configure(true, 180, 9, "Asia/Kolkata", 30);
        device = PushSubscription.builder().id("sub-1").userId(USER).endpoint("https://push.example/aaa")
                .timezone("Asia/Kolkata").active(true).lastSeenAt(Instant.now()).build();
        when(pushSubscriptionRepository.findByActiveTrue()).thenReturn(List.of(device));
        when(userAccountRepository.findById(USER))
                .thenReturn(Optional.of(UserAccount.builder().id(USER).timezone("Asia/Kolkata").build()));
        when(notificationRepository.findById(anyString())).thenReturn(Optional.empty());
        when(notificationRepository.findByUserIdAndStatusAndFireAtBetween(anyString(), any(), any(), any()))
                .thenReturn(List.of());
    }

    /** now = 10:00 IST on the given day. */
    private Instant nowAt(LocalTime time) {
        return ZonedDateTime.of(LocalDate.of(2026, 9, 21), time, IST).toInstant();
    }

    private DailyTask timedTask(String id, LocalDate date, String startTime) {
        return DailyTask.builder().id(id).userId(USER).title("Standup").date(date)
                .startTime(startTime).allDay(false).itemType("EVENT")
                .recurrenceFrequency("NONE").completed(false).deleted(false).build();
    }

    private ScheduledNotification captureInsert() {
        ArgumentCaptor<ScheduledNotification> cap = ArgumentCaptor.forClass(ScheduledNotification.class);
        verify(notificationRepository).insert(cap.capture());
        return cap.getValue();
    }

    @Test
    void plansAFutureOccurrenceAtTheRightInstant() {
        when(dailyTaskRepository.findCalendarCandidates(eq(USER), any(), any()))
                .thenReturn(List.of(timedTask("t1", LocalDate.of(2026, 9, 21), "11:30")));

        planner.planForUser(USER, List.of(device), nowAt(LocalTime.of(10, 0)));

        ScheduledNotification row = captureInsert();
        assertEquals(ZonedDateTime.of(LocalDate.of(2026, 9, 21), LocalTime.of(11, 30), IST).toInstant(),
                row.getFireAt());
        assertEquals(NotificationStatus.SCHEDULED, row.getStatus());
        assertEquals("Asia/Kolkata", row.getZoneId());
        assertEquals(USER, row.getUserId());
        assertNotNull(row.getActionToken());
    }

    /**
     * The bug behind "the popup appears the moment I open the app": an occurrence whose
     * time has already passed must never become a notification, no matter how often the
     * planner runs.
     */
    @Test
    void neverPlansAnOccurrenceThatAlreadyPassed() {
        when(dailyTaskRepository.findCalendarCandidates(eq(USER), any(), any()))
                .thenReturn(List.of(timedTask("t1", LocalDate.of(2026, 9, 21), "09:00")));

        planner.planForUser(USER, List.of(device), nowAt(LocalTime.of(10, 0)));

        verify(notificationRepository, never()).insert(any(ScheduledNotification.class));
    }

    @Test
    void neverPlansBeyondTheHorizon() {
        planner.configure(true, 30, 9, "Asia/Kolkata", 30);
        when(dailyTaskRepository.findCalendarCandidates(eq(USER), any(), any()))
                .thenReturn(List.of(timedTask("t1", LocalDate.of(2026, 9, 21), "23:00")));

        planner.planForUser(USER, List.of(device), nowAt(LocalTime.of(10, 0)));

        verify(notificationRepository, never()).insert(any(ScheduledNotification.class));
    }

    @Test
    void allDayItemFiresAtTheConfiguredLocalHour() {
        DailyTask allDay = DailyTask.builder().id("t2").userId(USER).title("Anniversary")
                .date(LocalDate.of(2026, 9, 21)).allDay(true).itemType("EVENT")
                .recurrenceFrequency("NONE").build();
        when(dailyTaskRepository.findCalendarCandidates(eq(USER), any(), any())).thenReturn(List.of(allDay));

        planner.planForUser(USER, List.of(device), nowAt(LocalTime.of(7, 30)));

        ScheduledNotification row = captureInsert();
        assertEquals(ZonedDateTime.of(LocalDate.of(2026, 9, 21), LocalTime.of(9, 0), IST).toInstant(),
                row.getFireAt());
        assertEquals(ScheduledNotification.KIND_ALL_DAY, row.getKind());
    }

    /** A device in another zone must not move the event; the event's own zone wins. */
    @Test
    void theEventsOwnTimezoneBeatsTheUserProfileZone() {
        // 11:30 in London is 16:00 IST, so the horizon has to reach past a naive IST reading.
        planner.configure(true, 720, 9, "Asia/Kolkata", 30);
        DailyTask task = timedTask("t1", LocalDate.of(2026, 9, 21), "11:30");
        task.setTimeZone("Europe/London");
        when(dailyTaskRepository.findCalendarCandidates(eq(USER), any(), any())).thenReturn(List.of(task));

        planner.planForUser(USER, List.of(device), nowAt(LocalTime.of(10, 0)));

        ScheduledNotification row = captureInsert();
        assertEquals(ZonedDateTime.of(LocalDate.of(2026, 9, 21), LocalTime.of(11, 30), ZoneId.of("Europe/London"))
                .toInstant(), row.getFireAt());
        assertEquals("Europe/London", row.getZoneId());
    }

    @Test
    void userProfileZoneBeatsTheDeviceZone() {
        when(userAccountRepository.findById(USER))
                .thenReturn(Optional.of(UserAccount.builder().id(USER).timezone("America/New_York").build()));
        assertEquals(ZoneId.of("America/New_York"), planner.resolveUserZone(USER, List.of(device)));
    }

    @Test
    void deviceZoneIsTheFallbackWhenTheProfileHasNone() {
        when(userAccountRepository.findById(USER)).thenReturn(Optional.empty());
        assertEquals(IST, planner.resolveUserZone(USER, List.of(device)));
    }

    @Test
    void unknownZonesFallBackRatherThanThrow() {
        when(userAccountRepository.findById(USER))
                .thenReturn(Optional.of(UserAccount.builder().id(USER).timezone("Mars/Olympus").build()));
        assertEquals(ZoneId.of("UTC"), planner.resolveUserZone(USER, List.of(device)));
    }

    @Test
    void completedOccurrenceIsNotPlanned() {
        DailyTask task = timedTask("t1", LocalDate.of(2026, 9, 21), "11:30");
        task.setItemType("TASK");
        task.setCompleted(true);
        when(dailyTaskRepository.findCalendarCandidates(eq(USER), any(), any())).thenReturn(List.of(task));

        planner.planForUser(USER, List.of(device), nowAt(LocalTime.of(10, 0)));

        verify(notificationRepository, never()).insert(any(ScheduledNotification.class));
    }

    @Test
    void softDeletedItemIsNotPlanned() {
        DailyTask task = timedTask("t1", LocalDate.of(2026, 9, 21), "11:30");
        task.setDeleted(true);
        when(dailyTaskRepository.findCalendarCandidates(eq(USER), any(), any())).thenReturn(List.of(task));

        planner.planForUser(USER, List.of(device), nowAt(LocalTime.of(10, 0)));

        verify(notificationRepository, never()).insert(any(ScheduledNotification.class));
    }

    @Test
    void skippedRecurringOccurrenceIsNotPlanned() {
        DailyTask task = DailyTask.builder().id("t1").userId(USER).title("Gym")
                .date(LocalDate.of(2026, 9, 1)).startTime("18:00").allDay(false).itemType("TASK")
                .recurrenceFrequency("DAILY")
                .excludedDates(List.of(LocalDate.of(2026, 9, 21)))
                .build();
        when(dailyTaskRepository.findCalendarCandidates(eq(USER), any(), any())).thenReturn(List.of(task));

        planner.planForUser(USER, List.of(device), nowAt(LocalTime.of(10, 0)));

        verify(notificationRepository, never()).insert(any(ScheduledNotification.class));
    }

    @Test
    void recurringOccurrenceIsPlannedPerDateWithADeterministicId() {
        DailyTask task = DailyTask.builder().id("t1").userId(USER).title("Gym")
                .date(LocalDate.of(2026, 9, 1)).startTime("18:00").allDay(false).itemType("TASK")
                .recurrenceFrequency("DAILY").build();
        when(dailyTaskRepository.findCalendarCandidates(eq(USER), any(), any())).thenReturn(List.of(task));

        planner.planForUser(USER, List.of(device), nowAt(LocalTime.of(17, 0)));

        ScheduledNotification row = captureInsert();
        assertEquals(ScheduledNotification.deterministicId(
                USER, ScheduledNotification.SOURCE_CALENDAR_ITEM, "t1",
                LocalDate.of(2026, 9, 21), ScheduledNotification.KIND_START), row.getId());
    }

    /** Re-planning the same occurrence must not create a second notification. */
    @Test
    void replanningAnUnchangedOccurrenceWritesNothing() {
        DailyTask task = timedTask("t1", LocalDate.of(2026, 9, 21), "11:30");
        when(dailyTaskRepository.findCalendarCandidates(eq(USER), any(), any())).thenReturn(List.of(task));
        Instant now = nowAt(LocalTime.of(10, 0));

        planner.planForUser(USER, List.of(device), now);
        ScheduledNotification planned = captureInsert();

        reset(notificationRepository);
        when(notificationRepository.findById(planned.getId())).thenReturn(Optional.of(planned));
        when(notificationRepository.findByUserIdAndStatusAndFireAtBetween(anyString(), any(), any(), any()))
                .thenReturn(List.of(planned));

        planner.planForUser(USER, List.of(device), now.plusSeconds(30));

        verify(notificationRepository, never()).insert(any(ScheduledNotification.class));
        verify(notificationRepository, never()).save(any(ScheduledNotification.class));
    }

    @Test
    void movingAnEventUpdatesThePendingRowInPlace() {
        DailyTask task = timedTask("t1", LocalDate.of(2026, 9, 21), "11:30");
        when(dailyTaskRepository.findCalendarCandidates(eq(USER), any(), any())).thenReturn(List.of(task));
        Instant now = nowAt(LocalTime.of(10, 0));
        planner.planForUser(USER, List.of(device), now);
        ScheduledNotification planned = captureInsert();

        reset(notificationRepository);
        when(notificationRepository.findById(planned.getId())).thenReturn(Optional.of(planned));
        when(notificationRepository.findByUserIdAndStatusAndFireAtBetween(anyString(), any(), any(), any()))
                .thenReturn(List.of(planned));
        task.setStartTime("12:15");

        planner.planForUser(USER, List.of(device), now);

        verify(notificationRepository, never()).insert(any(ScheduledNotification.class));
        ArgumentCaptor<ScheduledNotification> cap = ArgumentCaptor.forClass(ScheduledNotification.class);
        verify(notificationRepository).save(cap.capture());
        assertEquals(ZonedDateTime.of(LocalDate.of(2026, 9, 21), LocalTime.of(12, 15), IST).toInstant(),
                cap.getValue().getFireAt());
    }

    /** A notification already delivered is history; re-planning must never rewind it. */
    @Test
    void anAlreadySentRowIsNeverRewritten() {
        DailyTask task = timedTask("t1", LocalDate.of(2026, 9, 21), "11:30");
        when(dailyTaskRepository.findCalendarCandidates(eq(USER), any(), any())).thenReturn(List.of(task));
        String id = ScheduledNotification.deterministicId(
                USER, ScheduledNotification.SOURCE_CALENDAR_ITEM, "t1",
                LocalDate.of(2026, 9, 21), ScheduledNotification.KIND_START);
        ScheduledNotification sent = ScheduledNotification.builder()
                .id(id).userId(USER).status(NotificationStatus.SENT)
                .fireAt(Instant.parse("2026-09-21T05:00:00Z")).build();
        when(notificationRepository.findById(id)).thenReturn(Optional.of(sent));

        planner.planForUser(USER, List.of(device), nowAt(LocalTime.of(10, 0)));

        verify(notificationRepository, never()).insert(any(ScheduledNotification.class));
        verify(notificationRepository, never()).save(any(ScheduledNotification.class));
    }

    @Test
    void pendingRowWhoseOccurrenceVanishedIsCancelled() {
        when(dailyTaskRepository.findCalendarCandidates(eq(USER), any(), any())).thenReturn(List.of());
        ScheduledNotification orphan = ScheduledNotification.builder()
                .id("orphan").userId(USER).status(NotificationStatus.SCHEDULED)
                .fireAt(nowAt(LocalTime.of(11, 0))).build();
        when(notificationRepository.findByUserIdAndStatusAndFireAtBetween(anyString(), any(), any(), any()))
                .thenReturn(List.of(orphan));

        planner.planForUser(USER, List.of(device), nowAt(LocalTime.of(10, 0)));

        assertEquals(NotificationStatus.CANCELLED, orphan.getStatus());
        verify(notificationRepository).save(orphan);
    }

    @Test
    void usersWithoutAnActiveDeviceAreNotPlannedAtAll() {
        when(pushSubscriptionRepository.findByActiveTrue()).thenReturn(List.of());

        planner.planAllUsers(Instant.now());

        verifyNoInteractions(dailyTaskRepository);
    }

    @Test
    void severalDevicesForOneUserProduceOnePlan() {
        PushSubscription second = PushSubscription.builder().id("sub-2").userId(USER)
                .endpoint("https://push.example/bbb").timezone("Europe/London").active(true).build();
        when(pushSubscriptionRepository.findByActiveTrue()).thenReturn(List.of(device, second));
        when(dailyTaskRepository.findCalendarCandidates(eq(USER), any(), any()))
                .thenReturn(List.of(timedTask("t1", LocalDate.of(2026, 9, 21), "11:30")));

        planner.planAllUsers(nowAt(LocalTime.of(10, 0)));

        verify(dailyTaskRepository, times(1)).findCalendarCandidates(eq(USER), any(), any());
        verify(notificationRepository, times(1)).insert(any(ScheduledNotification.class));
    }

    @Test
    void oneBrokenItemDoesNotStopTheRest() {
        DailyTask broken = DailyTask.builder().id("bad").userId(USER).title("no date").build();
        when(dailyTaskRepository.findCalendarCandidates(eq(USER), any(), any()))
                .thenReturn(List.of(broken, timedTask("t1", LocalDate.of(2026, 9, 21), "11:30")));

        planner.planForUser(USER, List.of(device), nowAt(LocalTime.of(10, 0)));

        verify(notificationRepository, times(1)).insert(any(ScheduledNotification.class));
    }

    @Test
    void startTimeParsingToleratesTheFormatsTheUiProduces() {
        assertEquals(LocalTime.of(9, 5), NotificationPlanner.parseStartTime("9:05"));
        assertEquals(LocalTime.of(9, 5), NotificationPlanner.parseStartTime("09:05"));
        assertEquals(LocalTime.of(9, 5, 30), NotificationPlanner.parseStartTime("09:05:30"));
        assertEquals(LocalTime.of(9, 5), NotificationPlanner.parseStartTime(" 09:05 "));
        assertNull(NotificationPlanner.parseStartTime("morning"));
        assertNull(NotificationPlanner.parseStartTime(""));
        assertNull(NotificationPlanner.parseStartTime(null));
    }
}
