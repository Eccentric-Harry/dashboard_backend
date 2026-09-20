package com.personal_dashboard.backend.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.net.URI;
import java.time.Instant;

/**
 * One browser/device registration for Web Push.
 *
 * <p>The {@code endpoint} is globally unique (it is a per-device capability URL minted by
 * the push service), so it — not the user — is the identity of the row. That matters for
 * shared devices: when a second account subscribes from the same browser the row is
 * <em>claimed</em>, never duplicated, so the previous user's notifications stop reaching
 * a device they no longer own.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "push_subscriptions")
public class PushSubscription implements UserOwnedDocument {

    @Id
    private String id;

    private String userId;

    @Indexed(unique = true)
    private String endpoint;

    private String p256dh;
    private String auth;

    /** IANA zone reported by the device at registration. A fallback only — the user profile wins. */
    private String timezone;

    /** Stable per-browser id so the same install is recognisable after an endpoint rotation. */
    private String deviceId;

    private String userAgent;

    /**
     * False once the push service told us the endpoint is gone (404/410) or it failed
     * permanently. Kept rather than deleted so the dispatcher can report per-device
     * history, and so a re-subscribe reactivates one row instead of adding another.
     */
    @Builder.Default
    private Boolean active = true;

    private String inactiveReason;

    /** Consecutive transport failures. Reset on any success. */
    @Builder.Default
    private int failureCount = 0;

    private Instant lastFailureAt;

    private Instant lastSuccessAt;

    /** Refreshed whenever the client re-registers, so dead installs are identifiable. */
    private Instant lastSeenAt;

    @CreatedDate
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;

    public boolean isActive() {
        return !Boolean.FALSE.equals(active);
    }

    /** Scheme + host of the endpoint. Safe to log; the full URL is a credential. */
    public String endpointOrigin() {
        return originOf(endpoint);
    }

    public static String originOf(String endpoint) {
        if (endpoint == null || endpoint.isBlank()) return "unknown";
        try {
            URI uri = URI.create(endpoint);
            return uri.getScheme() + "://" + uri.getHost();
        } catch (RuntimeException e) {
            return "unknown";
        }
    }
}
