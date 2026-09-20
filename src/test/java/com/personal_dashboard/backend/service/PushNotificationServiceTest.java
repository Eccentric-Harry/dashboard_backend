package com.personal_dashboard.backend.service;

import com.personal_dashboard.backend.model.PushSubscription;
import com.personal_dashboard.backend.service.PushNotificationService.Kind;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * How a push service's HTTP answer is interpreted. Getting this wrong is expensive in both
 * directions: treat "gone" as retryable and a dead endpoint is hammered forever; treat
 * "busy" as permanent and a live device silently stops getting alerts.
 */
class PushNotificationServiceTest {

    @Test
    void successStatusesAreAccepted() {
        assertEquals(Kind.ACCEPTED, PushNotificationService.classify(201, null).kind());
        assertEquals(Kind.ACCEPTED, PushNotificationService.classify(200, null).kind());
        assertEquals(Kind.ACCEPTED, PushNotificationService.classify(202, null).kind());
    }

    @Test
    void goneEndpointsAreExpiredNotRetryable() {
        assertEquals(Kind.EXPIRED, PushNotificationService.classify(404, "").kind());
        assertEquals(Kind.EXPIRED, PushNotificationService.classify(410, "").kind());
    }

    @Test
    void rateLimitsAndServerErrorsAreRetryable() {
        assertEquals(Kind.RETRYABLE, PushNotificationService.classify(429, "slow down").kind());
        assertEquals(Kind.RETRYABLE, PushNotificationService.classify(500, "oops").kind());
        assertEquals(Kind.RETRYABLE, PushNotificationService.classify(503, "unavailable").kind());
    }

    @Test
    void clientErrorsArePermanent() {
        assertEquals(Kind.PERMANENT, PushNotificationService.classify(400, "bad encryption").kind());
        assertEquals(Kind.PERMANENT, PushNotificationService.classify(403, "vapid mismatch").kind());
        assertEquals(Kind.PERMANENT, PushNotificationService.classify(413, "payload too large").kind());
    }

    @Test
    void responseBodiesAreTruncatedBeforeTheyReachTheLogs() {
        String huge = "x".repeat(5000);

        String message = PushNotificationService.classify(500, huge).message();

        assertTrue(message.length() < 400);
        assertTrue(message.endsWith("…"));
    }

    @Test
    void endpointOriginIsSafeToLogAndNeverTheFullCapabilityUrl() {
        PushSubscription sub = PushSubscription.builder()
                .endpoint("https://fcm.googleapis.com/fcm/send/SECRET-TOKEN-VALUE").build();

        assertEquals("https://fcm.googleapis.com", sub.endpointOrigin());
        assertFalse(sub.endpointOrigin().contains("SECRET"));
    }

    @Test
    void malformedEndpointsDoNotBlowUpLogging() {
        assertEquals("unknown", PushSubscription.originOf("not a url at all"));
        assertEquals("unknown", PushSubscription.originOf(null));
        assertEquals("unknown", PushSubscription.originOf(""));
    }
}
