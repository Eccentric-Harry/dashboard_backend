package com.personal_dashboard.backend.service.notification;

import com.personal_dashboard.backend.model.DailyTask;
import com.personal_dashboard.backend.model.NotificationDelivery;
import com.personal_dashboard.backend.model.NotificationStatus;
import com.personal_dashboard.backend.model.PushSubscription;
import com.personal_dashboard.backend.model.ScheduledNotification;
import com.personal_dashboard.backend.repository.DailyTaskRepository;
import com.personal_dashboard.backend.repository.PushSubscriptionRepository;
import com.personal_dashboard.backend.repository.ScheduledNotificationRepository;
import com.personal_dashboard.backend.service.PushNotificationService;
import com.personal_dashboard.backend.service.PushNotificationService.Kind;
import com.personal_dashboard.backend.service.PushNotificationService.PushOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Delivery behaviour: what must happen once, what must never happen twice, and what must
 * deterministically not happen at all.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class NotificationDispatchJobTest {

    private static final String USER = "user-1";
    private static final Instant FIRE_AT = Instant.parse("2026-09-21T06:00:00Z");
    private static final LocalDate OCCURRENCE = LocalDate.of(2026, 9, 21);

    @Mock private NotificationClaimStore claimStore;
    @Mock private ScheduledNotificationRepository notificationRepository;
    @Mock private PushSubscriptionRepository pushSubscriptionRepository;
    @Mock private DailyTaskRepository dailyTaskRepository;
    @Mock private PushNotificationService pushNotificationService;

    @InjectMocks private NotificationDispatchJob job;

    private PushSubscription phone;
    private PushSubscription laptop;

    @BeforeEach
    void setUp() {
        job.configure(true, 50, 30, 5, 3);
        phone = PushSubscription.builder().id("sub-phone").userId(USER)
                .endpoint("https://fcm.googleapis.com/phone").active(true).build();
        laptop = PushSubscription.builder().id("sub-laptop").userId(USER)
                .endpoint("https://updates.push.services.mozilla.com/laptop").active(true).build();

        when(pushNotificationService.buildPayload(any())).thenReturn("{}");
        when(pushSubscriptionRepository.findByUserIdAndActiveTrue(USER)).thenReturn(List.of(phone));
        when(dailyTaskRepository.findByIdAndUserId("task-1", USER)).thenReturn(Optional.of(liveTask()));
        when(notificationRepository.save(any(ScheduledNotification.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    private DailyTask liveTask() {
        return DailyTask.builder().id("task-1").userId(USER).title("Standup")
                .date(OCCURRENCE).startTime("11:30").recurrenceFrequency("NONE")
                .completed(false).deleted(false).build();
    }

    private ScheduledNotification due() {
        return ScheduledNotification.builder()
                .id("n1").userId(USER)
                .sourceType(ScheduledNotification.SOURCE_CALENDAR_ITEM).sourceId("task-1")
                .occurrenceDate(OCCURRENCE).kind(ScheduledNotification.KIND_START)
                .title("Standup").body("Starts now · 11:30").url("/calendar")
                .fireAt(FIRE_AT).status(NotificationStatus.PROCESSING)
                .deliveries(new ArrayList<>())
                .build();
    }

    @Test
    void acceptedPush_marksSentWithPerDeviceRecord() {
        when(pushNotificationService.send(eq(phone), anyString()))
                .thenReturn(new PushOutcome(Kind.ACCEPTED, 201, null));
        ScheduledNotification row = due();

        job.process(row, FIRE_AT.plusSeconds(5));

        assertEquals(NotificationStatus.SENT, row.getStatus());
        assertNotNull(row.getSentAt());
        assertEquals(1, row.getDeliveries().size());
        assertTrue(row.getDeliveries().get(0).isSent());
        assertNull(row.getLockOwner());
        verify(notificationRepository).save(row);
    }

    /**
     * The anti-backlog rule. After a long outage the rows are still sitting there due; they
     * must be recorded and dropped, not fired hours late.
     */
    @Test
    void longOverdueNotification_isMissedNotSent() {
        ScheduledNotification row = due();

        job.process(row, FIRE_AT.plusSeconds(3 * 3600));

        assertEquals(NotificationStatus.MISSED, row.getStatus());
        assertTrue(row.getLastError().contains("late"));
        verify(pushNotificationService, never()).send(any(), anyString());
    }

    @Test
    void justSlightlyLateNotification_isStillDelivered() {
        when(pushNotificationService.send(eq(phone), anyString()))
                .thenReturn(new PushOutcome(Kind.ACCEPTED, 201, null));
        ScheduledNotification row = due();

        job.process(row, FIRE_AT.plusSeconds(120));

        assertEquals(NotificationStatus.SENT, row.getStatus());
    }

    @Test
    void completedTask_isCancelledAtDispatchTime() {
        DailyTask completed = liveTask();
        completed.setCompleted(true);
        when(dailyTaskRepository.findByIdAndUserId("task-1", USER)).thenReturn(Optional.of(completed));
        ScheduledNotification row = due();

        job.process(row, FIRE_AT);

        assertEquals(NotificationStatus.CANCELLED, row.getStatus());
        verify(pushNotificationService, never()).send(any(), anyString());
    }

    @Test
    void deletedTask_isCancelledAtDispatchTime() {
        DailyTask deleted = liveTask();
        deleted.setDeleted(true);
        when(dailyTaskRepository.findByIdAndUserId("task-1", USER)).thenReturn(Optional.of(deleted));
        ScheduledNotification row = due();

        job.process(row, FIRE_AT);

        assertEquals(NotificationStatus.CANCELLED, row.getStatus());
        assertEquals("source item was deleted", row.getLastError());
    }

    @Test
    void skippedOccurrence_isCancelledAtDispatchTime() {
        DailyTask skipped = liveTask();
        skipped.setExcludedDates(List.of(OCCURRENCE));
        when(dailyTaskRepository.findByIdAndUserId("task-1", USER)).thenReturn(Optional.of(skipped));
        ScheduledNotification row = due();

        job.process(row, FIRE_AT);

        assertEquals(NotificationStatus.CANCELLED, row.getStatus());
    }

    /** Alerts switched off between planning and firing. */
    @Test
    void noActiveDevice_cancelsRatherThanFails() {
        when(pushSubscriptionRepository.findByUserIdAndActiveTrue(USER)).thenReturn(List.of());
        ScheduledNotification row = due();

        job.process(row, FIRE_AT);

        assertEquals(NotificationStatus.CANCELLED, row.getStatus());
        assertTrue(row.getLastError().contains("alerts are off"));
        verify(pushNotificationService, never()).send(any(), anyString());
    }

    @Test
    void retryableFailure_goesBackToScheduledWithBackoff_andIsNotMarkedSent() {
        when(pushNotificationService.send(eq(phone), anyString()))
                .thenReturn(new PushOutcome(Kind.RETRYABLE, 503, "overloaded"));
        ScheduledNotification row = due();

        job.process(row, FIRE_AT);

        assertEquals(NotificationStatus.SCHEDULED, row.getStatus());
        assertEquals(1, row.getAttempts());
        assertNotNull(row.getNextAttemptAt());
        assertTrue(row.getNextAttemptAt().isAfter(FIRE_AT));
        assertNull(row.getSentAt());
    }

    @Test
    void retryBudgetExhausted_failsDeterministically() {
        when(pushNotificationService.send(eq(phone), anyString()))
                .thenReturn(new PushOutcome(Kind.RETRYABLE, 500, "boom"));
        ScheduledNotification row = due();
        row.setAttempts(2); // maxAttempts is 3 in this test's config

        job.process(row, FIRE_AT);

        assertEquals(NotificationStatus.FAILED, row.getStatus());
        assertTrue(row.getLastError().contains("retry budget"));
    }

    /**
     * Multi-device retry safety: the phone already took the payload, so a retry driven by
     * the laptop's failure must not push to the phone a second time.
     */
    @Test
    void retryOnlyRePushesDevicesThatHaveNotAcceptedYet() {
        when(pushSubscriptionRepository.findByUserIdAndActiveTrue(USER)).thenReturn(List.of(phone, laptop));
        ScheduledNotification row = due();
        row.setDeliveries(new ArrayList<>(List.of(
                NotificationDelivery.builder().subscriptionId("sub-phone").status("SENT")
                        .sentAt(FIRE_AT).attempts(1).build(),
                NotificationDelivery.builder().subscriptionId("sub-laptop").status("PENDING").attempts(1).build())));
        when(pushNotificationService.send(eq(laptop), anyString()))
                .thenReturn(new PushOutcome(Kind.ACCEPTED, 201, null));

        job.process(row, FIRE_AT.plusSeconds(60));

        verify(pushNotificationService, never()).send(eq(phone), anyString());
        verify(pushNotificationService).send(eq(laptop), anyString());
        assertEquals(NotificationStatus.SENT, row.getStatus());
    }

    @Test
    void oneDeviceAccepting_isEnoughToCountAsSent() {
        when(pushSubscriptionRepository.findByUserIdAndActiveTrue(USER)).thenReturn(List.of(phone, laptop));
        when(pushNotificationService.send(eq(phone), anyString()))
                .thenReturn(new PushOutcome(Kind.ACCEPTED, 201, null));
        when(pushNotificationService.send(eq(laptop), anyString()))
                .thenReturn(new PushOutcome(Kind.RETRYABLE, 503, "busy"));
        ScheduledNotification row = due();

        job.process(row, FIRE_AT);

        assertEquals(NotificationStatus.SENT, row.getStatus());
    }

    @Test
    void expiredEndpoint_deactivatesTheSubscriptionAndNeverRetriesIt() {
        when(pushNotificationService.send(eq(phone), anyString()))
                .thenReturn(new PushOutcome(Kind.EXPIRED, 410, "endpoint gone"));
        ScheduledNotification row = due();

        job.process(row, FIRE_AT);

        assertFalse(phone.isActive());
        assertTrue(phone.getInactiveReason().contains("410"));
        assertEquals(NotificationStatus.FAILED, row.getStatus());
        assertEquals("EXPIRED", row.getDeliveries().get(0).getStatus());
        verify(pushSubscriptionRepository).save(phone);
    }

    @Test
    void permanentRejection_deactivatesAndDoesNotRetry() {
        when(pushNotificationService.send(eq(phone), anyString()))
                .thenReturn(new PushOutcome(Kind.PERMANENT, 403, "vapid mismatch"));
        ScheduledNotification row = due();

        job.process(row, FIRE_AT);

        assertFalse(phone.isActive());
        assertEquals(NotificationStatus.FAILED, row.getStatus());
        assertEquals("no device could be reached", row.getLastError());
    }

    @Test
    void cycleClaimsUntilNothingIsDue() {
        when(pushNotificationService.send(any(), anyString())).thenReturn(new PushOutcome(Kind.ACCEPTED, 201, null));
        when(claimStore.claimNext(any(), anyString()))
                .thenReturn(due(), due(), null);

        int processed = job.runCycle(FIRE_AT);

        assertEquals(2, processed);
        verify(claimStore).releaseStaleClaims(any());
    }

    /** A second worker finds nothing to claim — the database, not the code, guarantees this. */
    @Test
    void aSecondWorkerCannotClaimTheSameRow() {
        when(pushNotificationService.send(any(), anyString())).thenReturn(new PushOutcome(Kind.ACCEPTED, 201, null));
        when(claimStore.claimNext(any(), anyString())).thenReturn(due(), null);

        assertEquals(1, job.runCycle(FIRE_AT));
        assertEquals(0, job.runCycle(FIRE_AT));
        verify(pushNotificationService, times(1)).send(any(), anyString());
    }

    @Test
    void batchSizeBoundsOneCycle() {
        job.configure(true, 2, 30, 5, 3);
        when(pushNotificationService.send(any(), anyString())).thenReturn(new PushOutcome(Kind.ACCEPTED, 201, null));
        when(claimStore.claimNext(any(), anyString())).thenAnswer(inv -> due());

        assertEquals(2, job.runCycle(FIRE_AT));
    }

    @Test
    void unexpectedFailure_neverLeavesARowStuckInProcessing() {
        when(pushNotificationService.buildPayload(any())).thenThrow(new IllegalStateException("kaboom"));
        when(claimStore.claimNext(any(), anyString())).thenReturn(due(), (ScheduledNotification) null);

        job.runCycle(FIRE_AT);

        verify(notificationRepository).save(argThat(saved ->
                saved.getStatus() == NotificationStatus.FAILED && saved.getLockOwner() == null));
    }

    @Test
    void disabledFlag_stopsDispatchEntirely() {
        job.configure(false, 50, 30, 5, 3);

        job.dispatch();

        verifyNoInteractions(claimStore);
    }

    @Test
    void backoffGrowsAndStaysBounded() {
        for (int attempt = 1; attempt <= 10; attempt++) {
            long backoff = NotificationDispatchJob.backoffMillis(attempt);
            assertTrue(backoff >= 1_000L, "backoff should never be instant");
            assertTrue(backoff <= 300_000L, "backoff should stay capped at five minutes");
        }
    }
}
