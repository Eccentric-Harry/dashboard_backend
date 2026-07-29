package com.personal_dashboard.backend.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "focus_sessions")
public class FocusSession implements UserOwnedDocument {

    @Id
    private String id;

    private String userId;

    private String activePursuit;

    private int durationMinutes;

    @Builder.Default
    private FocusSessionStatus status = FocusSessionStatus.IDLE;

    /**
     * Provenance of these minutes. Defaults to TIMER so the rows written before
     * this field existed — all of which came from the live timer — read correctly.
     */
    @Builder.Default
    private FocusSource source = FocusSource.TIMER;

    /** Free-text context, used by manual and calendar-imported sessions. */
    private String note;

    /**
     * For CALENDAR sessions, the calendar occurrenceId this was imported from.
     * Makes the import idempotent: an occurrence already present here is never
     * offered as a suggestion again, so re-running an import cannot double-count
     * a block. Null for TIMER and MANUAL.
     */
    private String sourceRefId;

    private Instant startTime;

    private Instant endTime;

    private Long remainingSecondsOnPause;

    @CreatedDate
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;
}
