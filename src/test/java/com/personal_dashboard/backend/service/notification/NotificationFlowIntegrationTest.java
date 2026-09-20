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
import com.personal_dashboard.backend.service.PushNotificationService;
import com.personal_dashboard.backend.service.PushNotificationService.Kind;
import com.personal_dashboard.backend.service.PushNotificationService.PushOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The whole path, with the database and the push service standing in as in-memory doubles:
 * calendar item → plan → due → atomic claim → push → SENT, and everything that must
 * <em>not</em> happen around it.
 *
 * <p>Deliberately not a {@code @SpringBootTest}: the local profile points at the production
 * Atlas cluster, so a Spring-context test here would plan and dispatch against real user data.
 */
class NotificationFlowIntegrationTest {

    private static final String USER = "user-1";
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalDate DAY = LocalDate.of(2026, 9, 21);

    private final Map<String, ScheduledNotification> store = new LinkedHashMap<>();
    private final List<String> pushed = new ArrayList<>();
    private final AtomicInteger pushCalls = new AtomicInteger();

    private ScheduledNotificationRepository notificationRepository;
    private PushSubscriptionRepository subscriptionRepository;
    private DailyTaskRepository taskRepository;
    private UserAccountRepository userAccountRepository;
    private PushNotificationService pushService;
    private NotificationClaimStore claimStore;

    private NotificationPlanner planner;
    private NotificationDispatchJob dispatcher;

    private PushSubscription phone;
    private DailyTask standup;

    private Instant at(LocalTime time) {
        return ZonedDateTime.of(DAY, time, IST).toInstant();
    }

    @BeforeEach
    void setUp() {
        notificationRepository = mock(ScheduledNotificationRepository.class);
        subscriptionRepository = mock(PushSubscriptionRepository.class);
        taskRepository = mock(DailyTaskRepository.class);
        userAccountRepository = mock(UserAccountRepository.class);
        pushService = mock(PushNotificationService.class);
        claimStore = mock(NotificationClaimStore.class);

        // ── in-memory scheduled_notifications ────────────────────────────────
        when(notificationRepository.findById(anyString()))
                .thenAnswer(inv -> Optional.ofNullable(store.get(inv.<String>getArgument(0))));
        when(notificationRepository.insert(any(ScheduledNotification.class))).thenAnswer(inv -> {
            ScheduledNotification row = inv.getArgument(0);
            if (store.containsKey(row.getId())) {
                // Exactly what Mongo does with a duplicate _id — the idempotency guarantee.
                throw new org.springframework.dao.DuplicateKeyException("duplicate _id " + row.getId());
            }
            store.put(row.getId(), row);
            return row;
        });
        when(notificationRepository.save(any(ScheduledNotification.class))).thenAnswer(inv -> {
            ScheduledNotification row = inv.getArgument(0);
            store.put(row.getId(), row);
            return row;
        });
        when(notificationRepository.findByUserIdAndStatusAndFireAtBetween(anyString(), any(), any(), any()))
                .thenAnswer(inv -> {
                    NotificationStatus status = inv.getArgument(1);
                    Instant from = inv.getArgument(2);
                    Instant to = inv.getArgument(3);
                    return store.values().stream()
                            .filter(row -> row.getStatus() == status)
                            .filter(row -> !row.getFireAt().isBefore(from) && !row.getFireAt().isAfter(to))
                            .toList();
                });

        // ── atomic claim, same contract as findAndModify ─────────────────────
        when(claimStore.claimNext(any(), anyString())).thenAnswer(inv -> {
            Instant now = inv.getArgument(0);
            Optional<ScheduledNotification> next = store.values().stream()
                    .filter(row -> row.getStatus() == NotificationStatus.SCHEDULED)
                    .filter(row -> !row.getFireAt().isAfter(now))
                    .filter(row -> row.getNextAttemptAt() == null || !row.getNextAttemptAt().isAfter(now))
                    .findFirst();
            next.ifPresent(row -> {
                row.setStatus(NotificationStatus.PROCESSING);
                row.setLockedAt(now);
                row.setLockOwner(inv.getArgument(1));
            });
            return next.orElse(null);
        });

        // ── calendar + devices + transport ──────────────────────────────────
        phone = PushSubscription.builder().id("sub-phone").userId(USER)
                .endpoint("https://fcm.googleapis.com/fcm/send/phone").timezone("Asia/Kolkata")
                .active(true).lastSeenAt(Instant.now()).build();
        standup = DailyTask.builder().id("task-1").userId(USER).title("Standup")
                .date(DAY).startTime("11:30").allDay(false).itemType("EVENT")
                .recurrenceFrequency("NONE").completed(false).deleted(false).build();

        when(subscriptionRepository.findByActiveTrue()).thenReturn(List.of(phone));
        when(subscriptionRepository.findByUserIdAndActiveTrue(USER)).thenReturn(List.of(phone));
        when(taskRepository.findCalendarCandidates(anyString(), any(), any())).thenReturn(List.of(standup));
        when(taskRepository.findByIdAndUserId("task-1", USER)).thenAnswer(inv -> Optional.of(standup));
        when(userAccountRepository.findById(USER))
                .thenReturn(Optional.of(UserAccount.builder().id(USER).timezone("Asia/Kolkata").build()));
        when(pushService.buildPayload(any())).thenAnswer(inv -> String.valueOf((Object) inv.getArgument(0)));
        when(pushService.send(any(), anyString())).thenAnswer(inv -> {
            pushCalls.incrementAndGet();
            pushed.add(inv.getArgument(1));
            return new PushOutcome(Kind.ACCEPTED, 201, null);
        });

        planner = new NotificationPlanner(taskRepository, subscriptionRepository,
                notificationRepository, userAccountRepository);
        planner.configure(true, 180, 9, "Asia/Kolkata", 30);
        dispatcher = new NotificationDispatchJob(claimStore, notificationRepository,
                subscriptionRepository, taskRepository, pushService);
        dispatcher.configure(true, 50, 30, 5, 3);
    }

    @Test
    void endToEnd_plannedOnce_firesOnce_atTheRightInstant() {
        planner.planAllUsers(at(LocalTime.of(10, 0)));
        assertEquals(1, store.size());

        // Before the instant: nothing goes out, however many cycles run.
        assertEquals(0, dispatcher.runCycle(at(LocalTime.of(10, 0))));
        assertEquals(0, dispatcher.runCycle(at(LocalTime.of(11, 29))));
        assertEquals(0, pushCalls.get());

        // At the instant: exactly one push.
        assertEquals(1, dispatcher.runCycle(at(LocalTime.of(11, 30))));
        assertEquals(1, pushCalls.get());
        assertEquals(NotificationStatus.SENT, store.values().iterator().next().getStatus());

        // And never again.
        assertEquals(0, dispatcher.runCycle(at(LocalTime.of(11, 31))));
        assertEquals(1, pushCalls.get());
    }

    /**
     * The reported bug, end to end: whatever the client does — reload, reopen the PWA, open
     * a second tab — the server side is untouched, so nothing fires early. Planning runs on
     * a timer here, standing in for "the app was opened N times".
     */
    @Test
    void repeatedPlanning_neverFiresEarlyAndNeverDuplicates() {
        for (int minute = 0; minute < 60; minute++) {
            planner.planAllUsers(at(LocalTime.of(10, 0).plusMinutes(minute)));
            dispatcher.runCycle(at(LocalTime.of(10, 0).plusMinutes(minute)));
        }

        assertEquals(1, store.size(), "one occurrence means one row, no matter how often we plan");
        assertEquals(0, pushCalls.get(), "nothing fired before 11:30");

        planner.planAllUsers(at(LocalTime.of(11, 30)));
        dispatcher.runCycle(at(LocalTime.of(11, 30)));
        assertEquals(1, pushCalls.get());

        // Keep planning and dispatching afterwards — the delivered row stays delivered.
        for (int minute = 1; minute <= 10; minute++) {
            planner.planAllUsers(at(LocalTime.of(11, 30).plusMinutes(minute)));
            dispatcher.runCycle(at(LocalTime.of(11, 30).plusMinutes(minute)));
        }
        assertEquals(1, pushCalls.get());
        assertEquals(1, store.size());
    }

    /** Starting the app up for the first time in the afternoon must not replay the morning. */
    @Test
    void coldStartAfterTheEventHasPassed_producesNothing() {
        planner.planAllUsers(at(LocalTime.of(14, 0)));
        dispatcher.runCycle(at(LocalTime.of(14, 0)));

        assertTrue(store.isEmpty());
        assertEquals(0, pushCalls.get());
    }

    /** A backend outage across the fire time: the row exists, but is dropped rather than fired late. */
    @Test
    void outageAcrossTheFireTime_marksMissedInsteadOfBlastingABacklog() {
        planner.planAllUsers(at(LocalTime.of(11, 0)));
        assertEquals(1, store.size());

        // ...backend down from 11:15 to 15:00, then the dispatcher starts again.
        dispatcher.runCycle(at(LocalTime.of(15, 0)));

        assertEquals(0, pushCalls.get());
        assertEquals(NotificationStatus.MISSED, store.values().iterator().next().getStatus());
    }

    @Test
    void deletingTheEventBeforeItFires_cancelsTheNotification() {
        planner.planAllUsers(at(LocalTime.of(10, 0)));
        standup.setDeleted(true);

        planner.planAllUsers(at(LocalTime.of(10, 1)));
        dispatcher.runCycle(at(LocalTime.of(11, 30)));

        assertEquals(0, pushCalls.get());
        assertEquals(NotificationStatus.CANCELLED, store.values().iterator().next().getStatus());
    }

    @Test
    void turningAlertsOffBeforeItFires_stopsTheNotification() {
        planner.planAllUsers(at(LocalTime.of(10, 0)));
        when(subscriptionRepository.findByUserIdAndActiveTrue(USER)).thenReturn(List.of());

        dispatcher.runCycle(at(LocalTime.of(11, 30)));

        assertEquals(0, pushCalls.get());
        assertEquals(NotificationStatus.CANCELLED, store.values().iterator().next().getStatus());
    }

    @Test
    void turningAlertsBackOn_reusesTheSameRowRatherThanAddingASecond() {
        planner.planAllUsers(at(LocalTime.of(10, 0)));
        String plannedId = store.keySet().iterator().next();

        // off, then on again before the event
        when(subscriptionRepository.findByActiveTrue()).thenReturn(List.of());
        planner.planAllUsers(at(LocalTime.of(10, 5)));
        when(subscriptionRepository.findByActiveTrue()).thenReturn(List.of(phone));
        planner.planAllUsers(at(LocalTime.of(10, 10)));

        assertEquals(1, store.size());
        assertEquals(plannedId, store.keySet().iterator().next());

        dispatcher.runCycle(at(LocalTime.of(11, 30)));
        assertEquals(1, pushCalls.get());
    }

    @Test
    void movingTheEventLater_firesAtTheNewTimeOnly() {
        planner.planAllUsers(at(LocalTime.of(10, 0)));
        standup.setStartTime("12:15");
        planner.planAllUsers(at(LocalTime.of(10, 1)));

        assertEquals(0, dispatcher.runCycle(at(LocalTime.of(11, 30))));
        assertEquals(0, pushCalls.get());

        assertEquals(1, dispatcher.runCycle(at(LocalTime.of(12, 15))));
        assertEquals(1, pushCalls.get());
    }

    @Test
    void twoDispatchersRacing_deliverExactlyOnce() {
        planner.planAllUsers(at(LocalTime.of(10, 0)));
        NotificationDispatchJob second = new NotificationDispatchJob(claimStore, notificationRepository,
                subscriptionRepository, taskRepository, pushService);
        second.configure(true, 50, 30, 5, 3);

        int a = dispatcher.runCycle(at(LocalTime.of(11, 30)));
        int b = second.runCycle(at(LocalTime.of(11, 30)));

        assertEquals(1, a + b);
        assertEquals(1, pushCalls.get());
    }

    @Test
    void theDeliveredPayloadCarriesTheIdentityTheServiceWorkerNeeds() {
        planner.planAllUsers(at(LocalTime.of(10, 0)));
        dispatcher.runCycle(at(LocalTime.of(11, 30)));

        String payload = pushed.get(0);
        assertTrue(payload.contains("Standup"));
        assertTrue(payload.contains("tag="), "the tag is what stops a repeat stacking a second banner");
        assertTrue(payload.contains("actionToken="), "snooze must work with the app closed");
    }

    @Test
    void recurringItem_firesOncePerOccurrence() {
        standup.setRecurrenceFrequency("DAILY");
        standup.setDate(DAY.minusDays(5));
        planner.configure(true, 2880, 9, "Asia/Kolkata", 30);

        planner.planAllUsers(at(LocalTime.of(10, 0)));

        // Today's 11:30 and tomorrow's 11:30 are both inside a 48h horizon.
        assertEquals(2, store.size());
        dispatcher.runCycle(at(LocalTime.of(11, 30)));
        assertEquals(1, pushCalls.get(), "tomorrow's occurrence is not due yet");
    }
}
