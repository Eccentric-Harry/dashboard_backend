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

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * The single source of truth for "a notification that should go out at an instant".
 *
 * <p>The planner materialises one of these per (user, source occurrence, kind); the
 * dispatcher claims it atomically when {@code fireAt} arrives and pushes it to the
 * user's devices. Nothing else may create, fire, or resurrect one — in particular no
 * browser: opening or reloading the app reads these records but can never cause one
 * to be delivered.
 *
 * <p><b>Idempotency</b> comes from {@link #deterministicId}: re-planning the same
 * occurrence produces the same {@code _id}, so a duplicate insert is rejected by Mongo
 * rather than creating a second notification. Planning is therefore safe to run as often
 * as we like, from as many instances as we like.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "scheduled_notifications")
public class ScheduledNotification implements UserOwnedDocument {

    public static final String SOURCE_CALENDAR_ITEM = "CALENDAR_ITEM";

    /** Timed occurrence — fires at the occurrence's start time. */
    public static final String KIND_START = "START";
    /** All-day occurrence — fires at the configured all-day hour. */
    public static final String KIND_ALL_DAY = "ALL_DAY";

    @Id
    private String id;

    private String userId;

    private String sourceType;

    private String sourceId;

    /** Occurrence date in the resolved zone — recurring items share one sourceId across dates. */
    private LocalDate occurrenceDate;

    private String kind;

    /** TASK | EVENT | REMINDER | MILESTONE — carried through so the client can style the entry. */
    private String itemType;

    private String title;
    private String body;
    private String url;

    /** Absolute instant this must fire at. Computed once from wall time + zone. */
    @Indexed
    private Instant fireAt;

    /** IANA zone the wall time was resolved in — kept for observability and DST debugging. */
    private String zoneId;

    /** The planned wall-clock time (HH:mm) in {@link #zoneId}. Never used for matching. */
    private String localTime;

    @Builder.Default
    private NotificationStatus status = NotificationStatus.SCHEDULED;

    @Builder.Default
    private int attempts = 0;

    /** Earliest instant a dispatcher may (re)claim this record. */
    private Instant nextAttemptAt;

    private String lastError;

    /** Set while PROCESSING so a crashed dispatcher's claim can be reclaimed. */
    private Instant lockedAt;

    private String lockOwner;

    @Builder.Default
    private List<NotificationDelivery> deliveries = List.of();

    private Instant sentAt;

    /** Set when a service worker confirms it displayed the notification. */
    private Instant acknowledgedAt;

    private Instant readAt;

    private Instant dismissedAt;

    /**
     * Opaque capability token embedded in the push payload so the service worker can
     * snooze without a user session (it has no access to localStorage). Scoped to this
     * one record and dies with it.
     */
    private String actionToken;

    /** Mongo TTL anchor — records self-purge so the collection cannot grow without bound. */
    private Instant expiresAt;

    @CreatedDate
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;

    /**
     * Stable id for one occurrence. Two planner runs (or two instances racing) produce
     * the same value, which is what makes "plan often" free of duplicates.
     *
     * <p>The separator is a dot, and every component is restricted to unreserved URI
     * characters, because this id travels as a <em>path segment</em> on
     * {@code /notifications/{id}/ack}. An earlier version joined with {@code '|'}, which
     * Tomcat rejects outright with a 400 before the request ever reaches a controller —
     * so acknowledging or reading a notification failed for every record.
     */
    public static String deterministicId(String userId, String sourceType, String sourceId,
                                         LocalDate occurrenceDate, String kind) {
        return String.join(".",
                urlSafe(userId), urlSafe(sourceType), urlSafe(sourceId),
                urlSafe(String.valueOf(occurrenceDate)), urlSafe(kind));
    }

    /** Keeps id components inside the unreserved set so the id is always a legal path segment. */
    static String urlSafe(String value) {
        if (value == null || value.isBlank()) {
            return "_";
        }
        return value.replaceAll("[^A-Za-z0-9_~-]", "_");
    }

    public boolean isTerminal() {
        return status == NotificationStatus.FAILED
                || status == NotificationStatus.CANCELLED
                || status == NotificationStatus.MISSED;
    }

    /** True once any device has it — the record must never be re-sent after this. */
    public boolean hasBeenDelivered() {
        return status == NotificationStatus.SENT || status == NotificationStatus.DELIVERED;
    }
}
