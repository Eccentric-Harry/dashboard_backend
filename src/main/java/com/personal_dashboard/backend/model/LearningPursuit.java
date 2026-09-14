package com.personal_dashboard.backend.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.ArrayList;
import java.util.List;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "learning_pursuits")
public class LearningPursuit implements UserOwnedDocument {

    @Id
    private String id;

    private String userId;

    private String title;
    private String category;
    private String notionUrl;

    /** Optional: what finishing this pursuit should let the learner do. Fed into tutor prompts. */
    private String goal;

    /**
     * The one "main" pursuit the Next-up panel and scheduling work from. At most one
     * per user; LearningPursuitService keeps that invariant.
     */
    @JsonProperty("isPrimary")
    private boolean primary;
    
    @Builder.Default
    private String status = "ACTIVE"; // "ACTIVE" or "COMPLETED"

    @Builder.Default
    private List<PursuitStep> steps = new ArrayList<>();

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PursuitStep {
        private String id; // Generated UUID
        private String text;

        /** Optional one-liner: why the step matters or what "done" looks like. */
        private String note;

        /**
         * For a step with children this is derived — true only when every child is
         * complete — and kept in sync by LearningPursuitService after each mutation.
         */
        @JsonProperty("isCompleted")
        private boolean isCompleted;

        /** When a leaf was ticked; cleared when un-ticked. */
        private Instant completedAt;

        /** Planned minutes for a leaf (AI-suggested or edited). Parents sum their leaves client-side. */
        private Integer estimateMinutes;

        /** Minutes actually logged against this step through step-linked focus sessions. */
        @Builder.Default
        private int spentMinutes = 0;

        /** One line on where the learner stopped, shown when they pick the step up again. */
        private String resumeNote;

        /** Key takeaways captured from a study session; carried into the learnings log. */
        private String takeaways;

        /** Sub-steps; documents written before nesting existed have none. */
        @Builder.Default
        private List<PursuitStep> children = new ArrayList<>();
    }

    @CreatedDate
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;
}
