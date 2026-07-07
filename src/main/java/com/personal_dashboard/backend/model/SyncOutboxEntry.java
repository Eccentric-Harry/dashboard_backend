package com.personal_dashboard.backend.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * Durable outbox record for an outbound push. One entry per local task change:
 * the drain worker loads the task and reconciles it against Google with retries,
 * so a crash during the remote push does not lose the change — the PENDING entry
 * is simply re-drained. Reconcile is idempotent, so re-runs are safe.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "sync_outbox")
public class SyncOutboxEntry {

    @Id
    private String id;

    @Indexed
    private String taskId;

    /** PENDING | DONE | FAILED */
    @Builder.Default
    private String status = "PENDING";

    @Builder.Default
    private int attempts = 0;

    /** Earliest time the worker may (re)attempt this entry. */
    @Indexed
    private Instant nextAttemptAt;

    private String lastError;

    private Instant createdAt;
    private Instant updatedAt;
}
