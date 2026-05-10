package com.personal_dashboard.backend.service;

import com.personal_dashboard.backend.dto.request.StravaActivityRequest;
import com.personal_dashboard.backend.model.StravaActivity;
import com.personal_dashboard.backend.repository.StravaActivityRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class StravaActivityService {

    private final StravaActivityRepository stravaActivityRepository;

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE;

    /**
     * Get all activities sorted by date descending
     */
    public List<StravaActivity> getAllActivities() {
        return stravaActivityRepository.findAllByOrderByDateDesc();
    }

    /**
     * Get activities in a date range
     */
    public List<StravaActivity> getActivitiesByDateRange(LocalDate start, LocalDate end) {
        return stravaActivityRepository.findByDateBetween(start, end);
    }

    /**
     * Compute aggregated stats for dashboard cards
     */
    public Map<String, Object> getActivityStats() {
        List<StravaActivity> activities = stravaActivityRepository.findAllByOrderByDateDesc();

        Map<String, Object> stats = new LinkedHashMap<>();

        // Total distance
        double totalDistanceKm = activities.stream()
                .mapToDouble(a -> a.getDistanceKm() != null ? a.getDistanceKm() : 0)
                .sum();
        stats.put("totalDistanceKm", Math.round(totalDistanceKm * 100.0) / 100.0);

        // Total activities
        stats.put("totalActivities", activities.size());

        // Total moving time (minutes)
        double totalMovingTimeMinutes = activities.stream()
                .mapToDouble(a -> a.getMovingTimeMinutes() != null ? a.getMovingTimeMinutes() : 0)
                .sum();
        stats.put("totalMovingTimeMinutes", Math.round(totalMovingTimeMinutes * 100.0) / 100.0);

        // Total elevation gain
        int totalElevation = activities.stream()
                .mapToInt(a -> a.getElevationGainMeters() != null ? a.getElevationGainMeters() : 0)
                .sum();
        stats.put("totalElevationMeters", totalElevation);

        // Best 5K pace (lowest pace for runs ≥ 4.9 km)
        OptionalDouble best5kPace = activities.stream()
                .filter(a -> "Run".equalsIgnoreCase(a.getSportType()))
                .filter(a -> a.getDistanceKm() != null && a.getDistanceKm() >= 4.9)
                .filter(a -> a.getPaceMinPerKm() != null)
                .mapToDouble(StravaActivity::getPaceMinPerKm)
                .min();
        stats.put("best5kPaceMinPerKm", best5kPace.isPresent() ? Math.round(best5kPace.getAsDouble() * 100.0) / 100.0 : null);

        // Format best 5K pace as "M:SS"
        if (best5kPace.isPresent()) {
            double pace = best5kPace.getAsDouble();
            int paceMin = (int) Math.floor(pace);
            int paceSec = (int) Math.round((pace - paceMin) * 60);
            stats.put("best5kPaceFormatted", String.format("%d:%02d", paceMin, paceSec));
        } else {
            stats.put("best5kPaceFormatted", "--:--");
        }

        // Activity count by sport type
        Map<String, Long> countBySport = activities.stream()
                .collect(Collectors.groupingBy(
                        a -> a.getSportType() != null ? a.getSportType() : "Unknown",
                        Collectors.counting()));
        stats.put("countBySportType", countBySport);

        // Distance by sport type
        Map<String, Double> distanceBySport = activities.stream()
                .collect(Collectors.groupingBy(
                        a -> a.getSportType() != null ? a.getSportType() : "Unknown",
                        Collectors.summingDouble(a -> a.getDistanceKm() != null ? a.getDistanceKm() : 0)));
        distanceBySport.replaceAll((k, v) -> Math.round(v * 100.0) / 100.0);
        stats.put("distanceBySportType", distanceBySport);

        // Current active weeks streak
        stats.put("currentStreakWeeks", computeWeeklyStreak(activities));

        // Recent activities with embed IDs (for the embed card)
        List<Map<String, String>> recentEmbeds = activities.stream()
                .filter(a -> a.getStravaEmbedId() != null && !a.getStravaEmbedId().isBlank())
                .limit(5)
                .map(a -> {
                    Map<String, String> embed = new HashMap<>();
                    embed.put("id", a.getStravaEmbedId());
                    embed.put("token", a.getStravaToken());
                    return embed;
                })
                .collect(Collectors.toList());
        stats.put("recentEmbeds", recentEmbeds);

        return stats;
    }

    /**
     * Create a single activity from manual entry
     */
    public StravaActivity createActivity(StravaActivityRequest request) {
        LocalDate date = LocalDate.parse(request.getDate(), DATE_FORMATTER);
        double movingTimeMinutes = parseMovingTimeToMinutes(request.getMovingTime());

        Double pace = null;
        if ("Run".equalsIgnoreCase(request.getSportType()) || "Walk".equalsIgnoreCase(request.getSportType())) {
            if (request.getDistanceKm() > 0) {
                pace = movingTimeMinutes / request.getDistanceKm();
                pace = Math.round(pace * 100.0) / 100.0;
            }
        }

        StravaActivity activity = StravaActivity.builder()
                .date(date)
                .activityName(request.getActivityName())
                .sportType(request.getSportType())
                .distanceKm(request.getDistanceKm())
                .movingTime(request.getMovingTime())
                .movingTimeMinutes(movingTimeMinutes)
                .elevationGainMeters(request.getElevationGainMeters())
                .paceMinPerKm(pace)
                .stravaEmbedId(request.getStravaEmbedId())
                .stravaToken(request.getStravaToken())
                .source(request.getStravaEmbedId() != null && !request.getStravaEmbedId().isBlank()
                        ? "strava-embed" : "manual")
                .build();

        StravaActivity saved = stravaActivityRepository.save(activity);
        log.info("Created Strava activity: {} on {}", saved.getActivityName(), saved.getDate());
        return saved;
    }

    /**
     * Bulk create activities (for initial seeding)
     */
    public List<StravaActivity> bulkCreateActivities(List<StravaActivityRequest> requests) {
        List<StravaActivity> activities = requests.stream().map(request -> {
            LocalDate date = LocalDate.parse(request.getDate(), DATE_FORMATTER);
            double movingTimeMinutes = parseMovingTimeToMinutes(request.getMovingTime());

            Double pace = null;
            if ("Run".equalsIgnoreCase(request.getSportType()) || "Walk".equalsIgnoreCase(request.getSportType())) {
                if (request.getDistanceKm() > 0) {
                    pace = movingTimeMinutes / request.getDistanceKm();
                    pace = Math.round(pace * 100.0) / 100.0;
                }
            }

            return StravaActivity.builder()
                    .date(date)
                    .activityName(request.getActivityName())
                    .sportType(request.getSportType())
                    .distanceKm(request.getDistanceKm())
                    .movingTime(request.getMovingTime())
                    .movingTimeMinutes(movingTimeMinutes)
                    .elevationGainMeters(request.getElevationGainMeters())
                    .paceMinPerKm(pace)
                    .stravaEmbedId(request.getStravaEmbedId())
                    .stravaToken(request.getStravaToken())
                    .source("strava-csv")
                    .build();
        }).collect(Collectors.toList());

        List<StravaActivity> saved = stravaActivityRepository.saveAll(activities);
        log.info("Bulk created {} Strava activities", saved.size());
        return saved;
    }

    /**
     * Parse moving time string to total minutes.
     * Supports "MM:SS" (e.g., "26:32") and "H:MM:SS" (e.g., "2:21:52")
     */
    public static double parseMovingTimeToMinutes(String movingTime) {
        if (movingTime == null || movingTime.isBlank()) return 0;

        String[] parts = movingTime.split(":");
        if (parts.length == 2) {
            // MM:SS
            int minutes = Integer.parseInt(parts[0].trim());
            int seconds = Integer.parseInt(parts[1].trim());
            return minutes + seconds / 60.0;
        } else if (parts.length == 3) {
            // H:MM:SS
            int hours = Integer.parseInt(parts[0].trim());
            int minutes = Integer.parseInt(parts[1].trim());
            int seconds = Integer.parseInt(parts[2].trim());
            return hours * 60 + minutes + seconds / 60.0;
        }

        return 0;
    }

    /**
     * Compute streak of consecutive weeks with at least one activity
     */
    private int computeWeeklyStreak(List<StravaActivity> activitiesSortedDesc) {
        if (activitiesSortedDesc.isEmpty()) return 0;

        Set<Long> activeWeeks = activitiesSortedDesc.stream()
                .map(a -> {
                    // Week number relative to epoch for comparison
                    return ChronoUnit.WEEKS.between(LocalDate.EPOCH, a.getDate());
                })
                .collect(Collectors.toSet());

        LocalDate now = LocalDate.now();
        long currentWeek = ChronoUnit.WEEKS.between(LocalDate.EPOCH, now);

        int streak = 0;
        long week = currentWeek;

        while (activeWeeks.contains(week)) {
            streak++;
            week--;
        }

        return streak;
    }
}
