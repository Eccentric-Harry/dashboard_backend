package com.personal_dashboard.backend.service.notification;

import com.personal_dashboard.backend.dto.NotificationView;
import com.personal_dashboard.backend.dto.PushSubscriptionStatus;
import com.personal_dashboard.backend.dto.request.PushRotateRequest;
import com.personal_dashboard.backend.dto.request.PushSubscriptionRequest;
import com.personal_dashboard.backend.model.NotificationStatus;
import com.personal_dashboard.backend.model.PushSubscription;
import com.personal_dashboard.backend.model.ScheduledNotification;
import com.personal_dashboard.backend.repository.PushSubscriptionRepository;
import com.personal_dashboard.backend.dto.PushTestResult;
import com.personal_dashboard.backend.repository.ScheduledNotificationRepository;
import com.personal_dashboard.backend.security.UserContext;
import com.personal_dashboard.backend.service.PushNotificationService;
import com.personal_dashboard.backend.service.PushNotificationService.Kind;
import com.personal_dashboard.backend.service.PushNotificationService.PushOutcome;
import org.junit.jupiter.api.AfterEach;
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
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** Registration idempotency, cross-account safety, feed state, and snooze. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class NotificationServiceTest {

    private static final String USER = "user-1";
    private static final String OTHER_USER = "user-2";
    private static final String ENDPOINT = "https://fcm.googleapis.com/fcm/send/abc";

    @Mock private PushSubscriptionRepository pushSubscriptionRepository;
    @Mock private ScheduledNotificationRepository notificationRepository;
    @Mock private NotificationPlanner notificationPlanner;
    @Mock private PushNotificationService pushNotificationService;

    @InjectMocks private NotificationService service;

    @BeforeEach
    void setUp() {
        service.configure(14, 30, 1440);
        UserContext.setUserId(USER);
        when(pushSubscriptionRepository.save(any(PushSubscription.class))).thenAnswer(inv -> inv.getArgument(0));
        when(notificationRepository.save(any(ScheduledNotification.class))).thenAnswer(inv -> inv.getArgument(0));
        when(pushSubscriptionRepository.findByUserIdAndActiveTrue(anyString())).thenReturn(List.of());
        when(pushNotificationService.buildPayload(any())).thenReturn("{}");
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private PushSubscriptionRequest request() {
        return PushSubscriptionRequest.builder()
                .endpoint(ENDPOINT).p256dh("key").auth("auth").timezone("Asia/Kolkata").deviceId("device-a")
                .build();
    }

    // ── Registration ─────────────────────────────────────────────────────────

    @Test
    void firstSubscribe_createsOneRow() {
        when(pushSubscriptionRepository.findByEndpoint(ENDPOINT)).thenReturn(Optional.empty());

        PushSubscription saved = service.register(request(), "Mozilla/5.0");

        assertEquals(USER, saved.getUserId());
        assertEquals(ENDPOINT, saved.getEndpoint());
        assertTrue(saved.isActive());
        assertNotNull(saved.getLastSeenAt());
        verify(pushSubscriptionRepository, times(1)).save(any(PushSubscription.class));
    }

    /** Re-opening the app, a second tab, or toggling alerts off and on must not duplicate. */
    @Test
    void repeatedSubscribe_updatesTheSameRow() {
        PushSubscription existing = PushSubscription.builder()
                .id("sub-1").userId(USER).endpoint(ENDPOINT).p256dh("old").auth("old")
                .active(false).failureCount(4).build();
        when(pushSubscriptionRepository.findByEndpoint(ENDPOINT)).thenReturn(Optional.of(existing));

        PushSubscription saved = service.register(request(), "Mozilla/5.0");

        assertEquals("sub-1", saved.getId());
        assertEquals("key", saved.getP256dh());
        assertTrue(saved.isActive(), "re-subscribing reactivates rather than adding a row");
        assertEquals(0, saved.getFailureCount());
        verify(pushSubscriptionRepository, times(1)).save(any(PushSubscription.class));
    }

    /**
     * Shared browser: user A logs out, user B logs in. The endpoint is the same physical
     * device, so it must stop belonging to A — otherwise A's reminders pop up for B.
     */
    @Test
    void subscribeFromADeviceRegisteredToAnotherAccount_reclaimsIt() {
        PushSubscription existing = PushSubscription.builder()
                .id("sub-1").userId(OTHER_USER).endpoint(ENDPOINT).active(true).build();
        when(pushSubscriptionRepository.findByEndpoint(ENDPOINT)).thenReturn(Optional.of(existing));

        PushSubscription saved = service.register(request(), "Mozilla/5.0");

        assertEquals(USER, saved.getUserId());
    }

    @Test
    void unsubscribe_removesTheRow() {
        PushSubscription existing = PushSubscription.builder()
                .id("sub-1").userId(USER).endpoint(ENDPOINT).active(true).build();
        when(pushSubscriptionRepository.findByEndpoint(ENDPOINT)).thenReturn(Optional.of(existing));

        assertTrue(service.unregister(ENDPOINT));

        verify(pushSubscriptionRepository).delete(existing);
    }

    @Test
    void unsubscribingTheLastDevice_cancelsEverythingStillPending() {
        PushSubscription existing = PushSubscription.builder()
                .id("sub-1").userId(USER).endpoint(ENDPOINT).active(true).build();
        when(pushSubscriptionRepository.findByEndpoint(ENDPOINT)).thenReturn(Optional.of(existing));
        when(pushSubscriptionRepository.findByUserIdAndActiveTrue(USER)).thenReturn(List.of());

        service.unregister(ENDPOINT);

        verify(notificationPlanner).cancelAllPending(eq(USER), anyString());
    }

    @Test
    void unsubscribingOneOfTwoDevices_leavesPendingNotificationsAlone() {
        PushSubscription existing = PushSubscription.builder()
                .id("sub-1").userId(USER).endpoint(ENDPOINT).active(true).build();
        when(pushSubscriptionRepository.findByEndpoint(ENDPOINT)).thenReturn(Optional.of(existing));
        when(pushSubscriptionRepository.findByUserIdAndActiveTrue(USER)).thenReturn(List.of(
                PushSubscription.builder().id("sub-2").userId(USER).endpoint("https://other").active(true).build()));

        service.unregister(ENDPOINT);

        verify(notificationPlanner, never()).cancelAllPending(anyString(), anyString());
    }

    @Test
    void unsubscribe_isIdempotentForAnUnknownEndpoint() {
        when(pushSubscriptionRepository.findByEndpoint(ENDPOINT)).thenReturn(Optional.empty());

        assertFalse(service.unregister(ENDPOINT));

        verify(pushSubscriptionRepository, never()).delete(any(PushSubscription.class));
    }

    @Test
    void unsubscribe_cannotRemoveAnotherAccountsDevice() {
        PushSubscription theirs = PushSubscription.builder()
                .id("sub-1").userId(OTHER_USER).endpoint(ENDPOINT).active(true).build();
        when(pushSubscriptionRepository.findByEndpoint(ENDPOINT)).thenReturn(Optional.of(theirs));

        assertFalse(service.unregister(ENDPOINT));

        verify(pushSubscriptionRepository, never()).delete(any(PushSubscription.class));
    }

    @Test
    void rotate_movesTheRowToTheNewEndpoint() {
        PushSubscription existing = PushSubscription.builder()
                .id("sub-1").userId(USER).endpoint(ENDPOINT).active(false).failureCount(3).build();
        when(pushSubscriptionRepository.findByEndpoint(ENDPOINT)).thenReturn(Optional.of(existing));
        when(pushSubscriptionRepository.findByEndpoint("https://fcm.googleapis.com/fcm/send/new"))
                .thenReturn(Optional.empty());

        assertTrue(service.rotate(PushRotateRequest.builder()
                .oldEndpoint(ENDPOINT).endpoint("https://fcm.googleapis.com/fcm/send/new")
                .p256dh("k2").auth("a2").timezone("Asia/Kolkata").build()));

        assertEquals("https://fcm.googleapis.com/fcm/send/new", existing.getEndpoint());
        assertTrue(existing.isActive());
        assertEquals(0, existing.getFailureCount());
        assertEquals(USER, existing.getUserId(), "a rotation must never change the owner");
    }

    @Test
    void rotate_ofAnUnknownEndpointIsANoOp() {
        when(pushSubscriptionRepository.findByEndpoint(anyString())).thenReturn(Optional.empty());

        assertFalse(service.rotate(PushRotateRequest.builder()
                .oldEndpoint("https://gone").endpoint("https://new").p256dh("k").auth("a").build()));
    }

    @Test
    void status_reportsThisDeviceAndTheAccountTotal() {
        when(pushSubscriptionRepository.findByUserIdAndActiveTrue(USER)).thenReturn(List.of(
                PushSubscription.builder().id("sub-1").userId(USER).endpoint(ENDPOINT).active(true)
                        .lastSeenAt(Instant.parse("2026-09-20T10:00:00Z")).build(),
                PushSubscription.builder().id("sub-2").userId(USER).endpoint("https://other").active(true).build()));

        PushSubscriptionStatus status = service.status(ENDPOINT);

        assertTrue(status.isRegistered());
        assertEquals("sub-1", status.getSubscriptionId());
        assertEquals(2, status.getActiveDeviceCount());
    }

    @Test
    void status_forAnUnknownEndpointIsNotRegistered() {
        when(pushSubscriptionRepository.findByUserIdAndActiveTrue(USER)).thenReturn(List.of());

        assertFalse(service.status("https://stale").isRegistered());
    }

    // ── Feed state ───────────────────────────────────────────────────────────

    // ── Test push ────────────────────────────────────────────────────────────

    /**
     * The point of a test push is telling "the transport is broken" apart from "your OS is
     * hiding it". Those look identical from the browser and need completely different fixes.
     */
    @Test
    void testPush_reportsPerDeviceOutcomes() {
        PushSubscription phone = PushSubscription.builder()
                .id("sub-phone").userId(USER).endpoint("https://fcm.googleapis.com/x").active(true).build();
        PushSubscription laptop = PushSubscription.builder()
                .id("sub-laptop").userId(USER).endpoint("https://web.push.apple.com/y").active(true).build();
        when(pushSubscriptionRepository.findByUserIdAndActiveTrue(USER)).thenReturn(List.of(phone, laptop));
        when(pushNotificationService.send(eq(phone), anyString())).thenReturn(new PushOutcome(Kind.ACCEPTED, 201, null));
        when(pushNotificationService.send(eq(laptop), anyString()))
                .thenReturn(new PushOutcome(Kind.PERMANENT, 403, "vapid mismatch"));

        PushTestResult result = service.sendTestPush();

        assertEquals(2, result.getDeviceCount());
        assertEquals(1, result.getAccepted());
        assertEquals("ACCEPTED", result.getOutcomes().get(0).getKind());
        assertEquals("PERMANENT", result.getOutcomes().get(1).getKind());
        assertEquals(403, result.getOutcomes().get(1).getStatusCode());
        assertEquals("vapid mismatch", result.getOutcomes().get(1).getMessage());
    }

    @Test
    void testPush_neverLeaksTheEndpointCapabilityUrl() {
        PushSubscription phone = PushSubscription.builder()
                .id("sub-phone").userId(USER)
                .endpoint("https://fcm.googleapis.com/fcm/send/SECRET-TOKEN").active(true).build();
        when(pushSubscriptionRepository.findByUserIdAndActiveTrue(USER)).thenReturn(List.of(phone));
        when(pushNotificationService.send(any(), anyString())).thenReturn(new PushOutcome(Kind.ACCEPTED, 201, null));

        PushTestResult result = service.sendTestPush();

        assertEquals("https://fcm.googleapis.com", result.getOutcomes().get(0).getEndpointOrigin());
        assertFalse(result.getOutcomes().get(0).getEndpointOrigin().contains("SECRET"));
    }

    @Test
    void testPush_deactivatesAnEndpointThePushServiceSaysIsGone() {
        PushSubscription phone = PushSubscription.builder()
                .id("sub-phone").userId(USER).endpoint("https://fcm.googleapis.com/x").active(true).build();
        when(pushSubscriptionRepository.findByUserIdAndActiveTrue(USER)).thenReturn(List.of(phone));
        when(pushNotificationService.send(any(), anyString()))
                .thenReturn(new PushOutcome(Kind.EXPIRED, 410, "gone"));

        service.sendTestPush();

        assertFalse(phone.isActive());
        verify(pushSubscriptionRepository).save(phone);
    }

    @Test
    void testPush_withNoDevicesReportsZeroRatherThanFailing() {
        when(pushSubscriptionRepository.findByUserIdAndActiveTrue(USER)).thenReturn(List.of());

        PushTestResult result = service.sendTestPush();

        assertEquals(0, result.getDeviceCount());
        assertEquals(0, result.getAccepted());
        assertTrue(result.getOutcomes().isEmpty());
    }

    private ScheduledNotification sent(String id) {
        return ScheduledNotification.builder()
                .id(id).userId(USER).title("Standup").fireAt(Instant.parse("2026-09-21T06:00:00Z"))
                .status(NotificationStatus.SENT).sentAt(Instant.parse("2026-09-21T06:00:01Z"))
                .build();
    }

    @Test
    void acknowledge_movesSentToDelivered() {
        ScheduledNotification row = sent("n1");
        when(notificationRepository.findByIdAndUserId("n1", USER)).thenReturn(Optional.of(row));

        NotificationView view = service.acknowledge("n1");

        assertEquals(NotificationStatus.DELIVERED, view.getStatus());
        assertNotNull(view.getAcknowledgedAt());
    }

    @Test
    void acknowledge_isIdempotent() {
        ScheduledNotification row = sent("n1");
        row.setStatus(NotificationStatus.DELIVERED);
        row.setAcknowledgedAt(Instant.parse("2026-09-21T06:00:05Z"));
        when(notificationRepository.findByIdAndUserId("n1", USER)).thenReturn(Optional.of(row));

        NotificationView view = service.acknowledge("n1");

        assertEquals(Instant.parse("2026-09-21T06:00:05Z"), view.getAcknowledgedAt());
    }

    @Test
    void acknowledge_cannotTouchAnotherUsersNotification() {
        when(notificationRepository.findByIdAndUserId("n1", USER)).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class, () -> service.acknowledge("n1"));
    }

    @Test
    void markAllRead_stampsEveryUnreadRow() {
        ScheduledNotification a = sent("n1");
        ScheduledNotification b = sent("n2");
        when(notificationRepository.findUnread(USER)).thenReturn(List.of(a, b));

        assertEquals(2, service.markAllRead());

        assertNotNull(a.getReadAt());
        assertNotNull(b.getReadAt());
        verify(notificationRepository).saveAll(List.of(a, b));
    }

    @Test
    void feedIsCappedAndNeverTriggersDelivery() {
        when(notificationRepository.findFeed(eq(USER), any(), any()))
                .thenReturn(List.of(sent("n1"), sent("n2"), sent("n3")));

        List<NotificationView> feed = service.getFeed(2);

        assertEquals(2, feed.size());
        verifyNoInteractions(pushSubscriptionRepository);
    }

    // ── Snooze ───────────────────────────────────────────────────────────────

    @Test
    void snooze_createsAFutureRowWithoutRewindingTheOriginal() {
        ScheduledNotification origin = sent("n1");
        origin.setActionToken("token-1");
        origin.setSourceId("task-1");
        when(notificationRepository.findByActionToken("token-1")).thenReturn(Optional.of(origin));

        Optional<NotificationView> view = service.snoozeByToken("token-1", 10);

        assertTrue(view.isPresent());
        assertTrue(view.get().getFireAt().isAfter(Instant.now()));
        assertEquals(NotificationStatus.SENT, origin.getStatus(), "history stays truthful");
        ArgumentCaptor<ScheduledNotification> cap = ArgumentCaptor.forClass(ScheduledNotification.class);
        verify(notificationRepository).insert(cap.capture());
        assertEquals(NotificationStatus.SCHEDULED, cap.getValue().getStatus());
        assertNotEquals("token-1", cap.getValue().getActionToken(), "a snooze mints a fresh token");
    }

    @Test
    void snooze_clampsARidiculousDuration() {
        ScheduledNotification origin = sent("n1");
        origin.setActionToken("token-1");
        when(notificationRepository.findByActionToken("token-1")).thenReturn(Optional.of(origin));

        service.snoozeByToken("token-1", 99_999);

        ArgumentCaptor<ScheduledNotification> cap = ArgumentCaptor.forClass(ScheduledNotification.class);
        verify(notificationRepository).insert(cap.capture());
        assertTrue(cap.getValue().getFireAt().isBefore(Instant.now().plusSeconds(1441 * 60)));
    }

    @Test
    void snooze_withAnUnknownTokenDoesNothing() {
        when(notificationRepository.findByActionToken("nope")).thenReturn(Optional.empty());

        assertTrue(service.snoozeByToken("nope", 10).isEmpty());
        verify(notificationRepository, never()).insert(any(ScheduledNotification.class));
    }

    @Test
    void doubleTapSnooze_collapsesToOneSchedule() {
        ScheduledNotification origin = sent("n1");
        origin.setActionToken("token-1");
        when(notificationRepository.findByActionToken("token-1")).thenReturn(Optional.of(origin));
        when(notificationRepository.insert(any(ScheduledNotification.class)))
                .thenAnswer(inv -> inv.getArgument(0))
                .thenThrow(new org.springframework.dao.DuplicateKeyException("duplicate id"));
        when(notificationRepository.findById(anyString()))
                .thenAnswer(inv -> Optional.of(sent(inv.getArgument(0))));

        assertTrue(service.snoozeByToken("token-1", 10).isPresent());
        assertTrue(service.snoozeByToken("token-1", 10).isPresent());

        verify(notificationRepository, times(2)).insert(any(ScheduledNotification.class));
    }
}
