package com.personal_dashboard.backend.model;

/**
 * Lifecycle of a single scheduled notification. The backend owns every transition;
 * nothing a browser does can move a record forward except {@link #DELIVERED}.
 *
 * <pre>
 *   SCHEDULED ──claim──> PROCESSING ──send ok──> SENT ──client ack──> DELIVERED
 *        │                    │
 *        │                    ├─ retryable ─> SCHEDULED (backoff, attempts++)
 *        │                    ├─ exhausted ─> FAILED
 *        │                    └─ too late  ─> MISSED
 *        └─ source removed / alerts off ─> CANCELLED
 * </pre>
 */
public enum NotificationStatus {

    /** Planned for a future instant. Not yet claimed by a dispatcher. */
    SCHEDULED,

    /** Claimed by exactly one dispatcher. A crash here is recovered by the stale-lock sweep. */
    PROCESSING,

    /** At least one push service accepted the payload for at least one device. */
    SENT,

    /** A client (service worker) confirmed it displayed the notification. */
    DELIVERED,

    /** No device could be reached and the retry budget is spent. Terminal. */
    FAILED,

    /** The source occurrence went away (deleted, completed, skipped) or alerts were switched off. Terminal. */
    CANCELLED,

    /**
     * Became due while nothing could deliver it (outage, long downtime) and is now too
     * stale to be useful. Deliberately never sent — this is what stops a restart from
     * blasting out hours of backlog. Terminal.
     */
    MISSED
}
