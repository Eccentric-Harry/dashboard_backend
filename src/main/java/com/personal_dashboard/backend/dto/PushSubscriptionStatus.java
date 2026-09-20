package com.personal_dashboard.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * What the client needs to reconcile its own UI state against the server on boot, so the
 * "Alerts On" toggle reflects reality rather than a localStorage flag that outlived the
 * subscription it described.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PushSubscriptionStatus {

    /** True when the endpoint the caller asked about is registered and active. */
    private boolean registered;

    private String subscriptionId;

    /** Active devices on this account — a user may have alerts on elsewhere but not here. */
    private int activeDeviceCount;

    private Instant lastSeenAt;
}
