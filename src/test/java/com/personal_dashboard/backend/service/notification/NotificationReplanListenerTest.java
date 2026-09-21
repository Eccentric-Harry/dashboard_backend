package com.personal_dashboard.backend.service.notification;

import com.personal_dashboard.backend.model.DailyTask;
import com.personal_dashboard.backend.model.PushSubscription;
import com.personal_dashboard.backend.repository.PushSubscriptionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.mongodb.core.mapping.event.AfterSaveEvent;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * The sweep alone leaves a hole a user will hit immediately: a task added a minute before it
 * is due. The sweep that would plan it runs after it is already due, and the planner never
 * plans the past — so without an on-write re-plan that notification is simply never created.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class NotificationReplanListenerTest {

    private static final String USER = "user-1";

    @Mock private NotificationPlanner notificationPlanner;
    @Mock private PushSubscriptionRepository pushSubscriptionRepository;

    @InjectMocks private NotificationReplanListener listener;

    private PushSubscription device;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(listener, "enabled", true);
        ReflectionTestUtils.setField(listener, "replanOnWrite", true);
        device = PushSubscription.builder().id("sub-1").userId(USER).active(true).build();
        when(pushSubscriptionRepository.findByUserIdAndActiveTrue(USER)).thenReturn(List.of(device));
    }

    private AfterSaveEvent<DailyTask> saveOf(DailyTask task) {
        return new AfterSaveEvent<>(task, new org.bson.Document(), "daily_tasks");
    }

    @Test
    void savingATaskReplansThatUserImmediately() {
        DailyTask task = DailyTask.builder().id("t1").userId(USER).title("Test Task").build();

        listener.onAfterSave(saveOf(task));

        verify(notificationPlanner).planForUser(eq(USER), eq(List.of(device)), any(Instant.class));
    }

    @Test
    void aUserWithNoActiveDeviceIsNotPlanned() {
        when(pushSubscriptionRepository.findByUserIdAndActiveTrue(USER)).thenReturn(List.of());

        listener.onAfterSave(saveOf(DailyTask.builder().id("t1").userId(USER).build()));

        verify(notificationPlanner, never()).planForUser(anyString(), any(), any());
    }

    @Test
    void aTaskWithoutAnOwnerIsIgnored() {
        listener.onAfterSave(saveOf(DailyTask.builder().id("t1").build()));

        verifyNoInteractions(pushSubscriptionRepository);
        verify(notificationPlanner, never()).planForUser(anyString(), any(), any());
    }

    /** A planning failure must never take the user's save down with it. */
    @Test
    void aPlanningFailureDoesNotPropagateIntoTheSave() {
        doThrow(new RuntimeException("mongo timeout"))
                .when(notificationPlanner).planForUser(anyString(), any(), any());

        listener.onAfterSave(saveOf(DailyTask.builder().id("t1").userId(USER).build()));
        // no exception escapes
    }

    @Test
    void theFeatureCanBeSwitchedOff() {
        ReflectionTestUtils.setField(listener, "replanOnWrite", false);

        listener.onAfterSave(saveOf(DailyTask.builder().id("t1").userId(USER).build()));

        verify(notificationPlanner, never()).planForUser(anyString(), any(), any());
    }

    @Test
    void theWholeNotificationSystemCanBeSwitchedOff() {
        ReflectionTestUtils.setField(listener, "enabled", false);

        listener.onAfterSave(saveOf(DailyTask.builder().id("t1").userId(USER).build()));

        verify(notificationPlanner, never()).planForUser(anyString(), any(), any());
    }
}
