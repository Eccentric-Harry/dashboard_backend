package com.personal_dashboard.backend.service;

import com.personal_dashboard.backend.dto.WorkoutsMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class WorkoutsService {

    private final CacheService cacheService;
    private static final String WORKOUTS_CACHE_ID = "workouts";

    /**
     * Get cached workouts data. If not present, returns an empty default to prevent 503 errors.
     */
    public WorkoutsMetrics getCachedWorkoutsData() {
        try {
            log.info("Fetching workouts data from RDH Cache");

            return cacheService.getCacheData(WORKOUTS_CACHE_ID, WorkoutsMetrics.class)
                    .orElseGet(() -> {
                        log.warn("No cached workouts data available - returning empty fallback data");
                        return buildEmptyWorkoutsMetrics();
                    });
        } catch (Exception e) {
            log.error("Error fetching cached workouts data", e);
            return buildEmptyWorkoutsMetrics();
        }
    }

    /**
     * Manually save external workouts data into the RDH cache
     */
    public WorkoutsMetrics updateWorkoutsData(WorkoutsMetrics metrics) {
        try {
            cacheService.saveCacheData(WORKOUTS_CACHE_ID, metrics, "manual_upload");
            return metrics;
        } catch (Exception e) {
            log.error("Error updating workouts data in cache", e);
            throw new RuntimeException("Failed to update workouts data", e);
        }
    }

    /**
     * Get only recent activities from cache
     */
    public List<WorkoutsMetrics.ActivitySummary> getRecentActivities(int limit) {
        WorkoutsMetrics metrics = getCachedWorkoutsData();
        
        if (metrics.getRecent() == null || metrics.getRecent().getActivities() == null) {
            return List.of();
        }
        
        return metrics.getRecent().getActivities().stream()
                .limit(limit)
                .collect(Collectors.toList());
    }

    /**
     * Get all activities from cache
     */
    public List<WorkoutsMetrics.ActivitySummary> getAllActivities() {
        WorkoutsMetrics metrics = getCachedWorkoutsData();
        
        if (metrics.getRecent() == null || metrics.getRecent().getActivities() == null) {
            return List.of();
        }
        
        return metrics.getRecent().getActivities();
    }

    /**
     * Builds a safe fallback object to avoid UI crashes when the cache is empty
     */
    private WorkoutsMetrics buildEmptyWorkoutsMetrics() {
        return WorkoutsMetrics.builder()
                .athlete(WorkoutsMetrics.AthleteProfile.builder()
                        .name("Local Runner")
                        .location("Local")
                        .build())
                .stats(WorkoutsMetrics.ActivityStats.builder().build())
                .recent(WorkoutsMetrics.RecentActivities.builder()
                        .activities(List.of())
                        .count(0)
                        .build())
                .build();
    }
}
