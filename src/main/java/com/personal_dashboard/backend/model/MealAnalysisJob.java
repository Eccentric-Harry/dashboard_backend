package com.personal_dashboard.backend.model;

import com.personal_dashboard.backend.dto.MealAnalysisResponse;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * Tracks one asynchronous AI meal-analysis run.
 *
 * <p>{@code POST /meals/analyze} creates a job in {@code PENDING}, hands the work
 * to the background executor, and returns the id immediately. The client polls
 * {@code GET /meals/analyze/{jobId}} until the status is terminal
 * ({@code COMPLETED} or {@code FAILED}). Because status lives in MongoDB, polling
 * works across instances and survives the original request being dropped.
 *
 * <p>Documents self-expire via a TTL index so finished jobs don't accumulate.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "meal_analysis_jobs")
public class MealAnalysisJob {

    public enum Status {
        PENDING,
        PROCESSING,
        COMPLETED,
        FAILED
    }

    @Id
    private String id;

    private String userId;

    private Status status;

    private String mealType;

    private String date;

    private String description;

    /** Populated when {@link Status#COMPLETED}. */
    private MealAnalysisResponse result;

    /** Machine-readable failure source when {@link Status#FAILED} (e.g. "gemini-error"). */
    private String errorSource;

    private Instant createdAt;

    private Instant updatedAt;

    /**
     * TTL anchor — MongoDB removes the document ~2 hours after creation so
     * completed/failed jobs are cleaned up automatically.
     */
    @Indexed(name = "meal_analysis_job_ttl", expireAfterSeconds = 7200)
    private Instant expiresAt;
}
