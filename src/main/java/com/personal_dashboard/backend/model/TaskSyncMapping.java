package com.personal_dashboard.backend.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * Links one local {@link DailyTask} to its copy in one Google <em>Tasks</em> account.
 * The Google Tasks analogue of {@link CalendarSyncMapping} — deliberately a separate
 * collection, because a task can be mirrored into Google Tasks and Google Calendar
 * independently and the two must not share a row.
 *
 * <p>Unlike Calendar, the Tasks API does not accept a client-supplied id on insert —
 * the id is always server-generated — so this mapping is the <em>only</em> link back
 * to the remote copy. Losing it means the next push creates a duplicate, which is why
 * it is written in the same bypassed save as the task itself.
 *
 * <p>_id is a composite: taskId + ":" + accountEmail.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "task_sync_mappings")
@CompoundIndexes({
    @CompoundIndex(def = "{'googleTaskId': 1, 'userId': 1}"),
    @CompoundIndex(def = "{'taskId': 1, 'accountEmail': 1}", unique = true)
})
public class TaskSyncMapping implements UserOwnedDocument {

    @Id
    private String id; // taskId + ":" + accountEmail

    @Indexed
    private String taskId;

    @Indexed
    private String userId;

    private String accountEmail;

    /** Server-generated Google Tasks task id. */
    private String googleTaskId;

    /** The Google task list the remote copy lives in (mirrors the local category). */
    private String googleTaskListId;

    /** Google's etag for the remote copy, captured on the last pull or push. */
    private String etag;

    /**
     * Google's own {@code updated} timestamp for the copy we last wrote or read.
     * This is what suppresses echoes: a polled task whose {@code updated} still equals
     * this value is our own write coming back, not a change made on the phone.
     * Stored as Google's clock, never ours — the two are not comparable.
     */
    private Instant remoteUpdatedAt;

    /** SYNCED | CANCELLED | ERROR. */
    @Builder.Default
    private String syncState = "SYNCED";

    private Instant lastSyncedAt;

    public static String compositeId(String taskId, String accountEmail) {
        return taskId + ":" + accountEmail;
    }
}
