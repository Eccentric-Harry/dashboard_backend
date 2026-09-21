package com.personal_dashboard.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

/**
 * Everything needed to answer "why did my alert not arrive?" without shell access to the
 * database. Delivery failures are per-device and mostly invisible from the client — the push
 * service's rejection reason lands in a log line nobody is reading at the time.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NotificationDiagnostics {

    private boolean enabled;
    private Instant serverTime;
    private String resolvedTimezone;
    private String serverTimeLocal;

    private List<DeviceInfo> devices;
    private List<ScheduledInfo> upcoming;
    private List<AttemptInfo> recent;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DeviceInfo {
        private String id;
        /** Push service host only — the full endpoint is a credential. */
        private String endpointOrigin;
        private boolean active;
        private String inactiveReason;
        private String timezone;
        private String userAgent;
        private int failureCount;
        private Instant lastSeenAt;
        private Instant lastSuccessAt;
        private Instant lastFailureAt;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ScheduledInfo {
        private String id;
        private String title;
        private Instant fireAt;
        private String fireAtLocal;
        private String status;
        private String zoneId;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AttemptInfo {
        private String id;
        private String title;
        private Instant fireAt;
        private String status;
        private int attempts;
        private String lastError;
        private Instant sentAt;
        private Instant acknowledgedAt;
        /** One line per device: "SENT 201", "FAILED 403 vapid mismatch", … */
        private List<String> deliveries;
    }
}
