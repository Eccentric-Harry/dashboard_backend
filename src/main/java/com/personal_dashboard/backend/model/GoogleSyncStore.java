package com.personal_dashboard.backend.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * Stores OAuth credentials and webhook state for one Google Calendar account.
 *
 * _id is the composite storeId = userId + ":" + email, so a single user can
 * connect multiple Google accounts simultaneously.  Use GoogleSyncStore.storeId()
 * to build the key and GoogleSyncStoreRepository.findByUserId() to list all
 * accounts for a user.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "google_sync_stores")
@CompoundIndex(def = "{'userId': 1, 'email': 1}", unique = true)
public class GoogleSyncStore {

    /**
     * Composite primary key: userId + ":" + email.
     * Allows multiple Google Calendar accounts per user.
     */
    @Id
    private String id;

    @Indexed
    private String userId;

    private String email;

    // Encrypted access / refresh tokens
    private String accessToken;
    private String refreshToken;

    private String currentSyncToken;
    private String webhookChannelId;
    private String webhookResourceId;
    private Instant webhookExpiration;

    private Instant lastSyncedAt;

    /**
     * Connection state: CONNECTED (default) or DISCONNECTED. Set to DISCONNECTED
     * when the refresh token is revoked/invalid, instead of silently deleting the
     * store — so the account surfaces a clean "reconnect needed" state to the user
     * and sync aborts gracefully rather than crashing.
     */
    @Builder.Default
    private String status = "CONNECTED";

    /** Human-readable reason the account became DISCONNECTED (nullable). */
    private String authError;

    private Instant disconnectedAt;

    /**
     * Per-account opt-in for cross-account fan-out: when true, events that were
     * PULLED from another Google account may be pushed into THIS account. Default
     * false so shared invites don't silently fan out across every connected calendar.
     */
    @Builder.Default
    private Boolean crossAccountPush = false;

    // ─── Google Tasks sync (separate API, separate opt-in) ──────────────────────

    /**
     * Per-account opt-in for mirroring planner tasks into Google Tasks. Off by default:
     * enabling it creates task lists in the user's Google account, which is theirs to
     * agree to rather than something a calendar connection should imply.
     */
    @Builder.Default
    private Boolean tasksSyncEnabled = false;

    /**
     * Whether this account's OAuth grant actually covers the Tasks scope. Accounts
     * connected before Tasks sync existed were granted Calendar only, so their tokens
     * are valid but Tasks calls 403. Tracked separately from {@link #status} because
     * the account is not disconnected — it just needs re-consent for one more scope.
     */
    @Builder.Default
    private Boolean tasksScopeGranted = false;

    /**
     * Incremental-poll cursor: the {@code updatedMin} floor for the next poll.
     * The Tasks API has no sync tokens, so this timestamp is the entire cursor —
     * if it is lost the next poll degrades to a full read, which is safe but slower.
     */
    private Instant tasksLastPolledAt;

    /** Last time a Tasks poll completed without throwing (for the status card). */
    private Instant tasksLastSyncedAt;

    public boolean isTasksSyncEnabled() {
        return Boolean.TRUE.equals(tasksSyncEnabled);
    }

    public boolean isDisconnected() {
        return "DISCONNECTED".equalsIgnoreCase(status);
    }

    public static String storeId(String userId, String email) {
        return userId + ":" + email;
    }
}
