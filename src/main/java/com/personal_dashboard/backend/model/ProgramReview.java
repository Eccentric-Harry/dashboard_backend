package com.personal_dashboard.backend.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * The weekly (Sunday) review of a {@link Program}: one self-trust number, one win, one thing
 * that got in the way, one adjustment and an if-then plan for next week — and the only place
 * a track's target can change ({@link #changes} records what moved, for the logbook).
 * One per (program, week); saving again rewrites it.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "program_reviews")
public class ProgramReview implements UserOwnedDocument {

    @Id
    private String id;

    private String userId;

    private String programId;

    /** Monday of the week reviewed. */
    private LocalDate weekStart;

    /** "I trust myself to do what I say I will", 1–10. */
    private Integer selfTrust;

    private String win;

    private String obstacle;

    private String adjustment;

    private String ifThen;

    private List<TargetChange> changes;

    private Instant createdAt;

    private Instant updatedAt;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TargetChange {
        private String track;
        private Double fromTarget;
        private Double toTarget;
        private Double fromFloor;
        private Double toFloor;
    }
}
