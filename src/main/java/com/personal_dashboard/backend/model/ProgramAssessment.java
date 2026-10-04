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
 * A slow outcome measure on a {@link Program}: the Rosenberg Self-Esteem Scale (days 1, 45,
 * 90), the WHO-5 Well-Being Index (every two weeks), or a body check (photos, weight, waist —
 * every two weeks). Questionnaire scores are computed server-side by util/ProgramScoring.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "program_assessments")
public class ProgramAssessment implements UserOwnedDocument {

    public static final String ROSENBERG = "ROSENBERG";
    public static final String WHO5 = "WHO5";
    public static final String BODY = "BODY";

    @Id
    private String id;

    private String userId;

    private String programId;

    private LocalDate date;

    /** ROSENBERG, WHO5 or BODY. */
    private String type;

    /** Rosenberg: 10 answers, 0 (strongly disagree) – 3 (strongly agree), as answered. WHO-5: 5 answers, 0–5. */
    private List<Integer> answers;

    /** Rosenberg 0–30 (reverse items applied); WHO-5 0–100. Null for BODY. */
    private Integer score;

    private Double weightKg;

    private Double waistCm;

    /** Photos taken with a body check (program_media ids). */
    private List<String> mediaIds;

    private String note;

    private Instant createdAt;
}
