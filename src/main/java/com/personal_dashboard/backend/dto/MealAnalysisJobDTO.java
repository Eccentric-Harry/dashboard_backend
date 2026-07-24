package com.personal_dashboard.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Client-facing view of an asynchronous meal-analysis job.
 *
 * <ul>
 *   <li>{@code POST /meals/analyze} returns this with {@code status = PENDING} and the {@code jobId}.</li>
 *   <li>{@code GET /meals/analyze/{jobId}} returns the current {@code status}; when
 *       {@code COMPLETED} the {@code result} is populated, when {@code FAILED} the
 *       {@code errorSource} explains why.</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MealAnalysisJobDTO {

    private String jobId;

    /** PENDING | PROCESSING | COMPLETED | FAILED */
    private String status;

    /** Full analysis payload — only present when status is COMPLETED. */
    private MealAnalysisResponse result;

    /** Machine-readable failure reason — only present when status is FAILED. */
    private String errorSource;
}
