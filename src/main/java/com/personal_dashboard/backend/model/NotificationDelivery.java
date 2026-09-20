package com.personal_dashboard.backend.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Per-device outcome of one scheduled notification. Kept on the parent record so a
 * retry can skip every endpoint that already accepted the payload — that is what makes
 * a retry safe for a user with several devices: the phone is not pushed twice because
 * the laptop's endpoint was rate-limited.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NotificationDelivery {

    /** PENDING | SENT | EXPIRED | FAILED */
    @Builder.Default
    private String status = "PENDING";

    private String subscriptionId;

    /** Endpoint origin only (never the full capability URL) — enough to debug, not enough to push. */
    private String endpointOrigin;

    @Builder.Default
    private int attempts = 0;

    private Integer statusCode;

    private String lastError;

    private Instant sentAt;

    public boolean isSent() {
        return "SENT".equals(status);
    }

    /** Terminal outcomes are never retried; only PENDING deliveries are attempted again. */
    public boolean isTerminal() {
        return "SENT".equals(status) || "EXPIRED".equals(status) || "FAILED".equals(status);
    }
}
