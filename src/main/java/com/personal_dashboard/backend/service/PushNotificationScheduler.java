package com.personal_dashboard.backend.service;

import com.personal_dashboard.backend.model.DailyTask;
import com.personal_dashboard.backend.model.PushSubscription;
import com.personal_dashboard.backend.model.PushedOccurrenceLog;
import com.personal_dashboard.backend.repository.DailyTaskRepository;
import com.personal_dashboard.backend.repository.PushSubscriptionRepository;
import com.personal_dashboard.backend.repository.PushedOccurrenceLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class PushNotificationScheduler {

    private final DailyTaskRepository dailyTaskRepository;
    private final PushSubscriptionRepository pushSubscriptionRepository;
    private final PushedOccurrenceLogRepository pushedOccurrenceLogRepository;
    private final PushNotificationService pushNotificationService;

    @Scheduled(fixedRate = 60000) // Trigger task every minute
    public void runSchedulerCycle() {
        List<PushSubscription> subscriptions = pushSubscriptionRepository.findAll();
        if (subscriptions.isEmpty()) {
            return;
        }

        log.debug("Starting Web Push scheduler cycle for {} subscriptions", subscriptions.size());

        for (PushSubscription sub : subscriptions) {
            try {
                // Determine the timezone of the device
                ZoneId zoneId;
                try {
                    zoneId = ZoneId.of(sub.getTimezone() != null ? sub.getTimezone() : "UTC");
                } catch (Exception tzEx) {
                    log.warn("Invalid timezone '{}' for subscription {}, falling back to UTC.", sub.getTimezone(), sub.getId());
                    zoneId = ZoneId.of("UTC");
                }

                // Resolve current date and time in the device's local timezone
                ZonedDateTime localTime = ZonedDateTime.now(zoneId);
                String currentHHMM = localTime.format(DateTimeFormatter.ofPattern("HH:mm"));
                LocalDate localDate = localTime.toLocalDate();

                // Fetch calendar items that might occur on this day
                List<DailyTask> candidates = dailyTaskRepository.findCalendarCandidates(localDate, localDate.plusDays(1));

                for (DailyTask item : candidates) {
                    if (item.getId() == null) continue;

                    // Skip tasks or reminders that are already completed
                    if (("TASK".equals(item.getItemType()) || "REMINDER".equals(item.getItemType())) 
                            && Boolean.TRUE.equals(item.getCompleted())) {
                        continue;
                    }

                    // Check if this occurrence falls on today
                    if (!occursOnDate(item, localDate)) {
                        continue;
                    }

                    boolean matchesTime = false;
                    String alertMessage = "";

                    if (Boolean.TRUE.equals(item.getAllDay())) {
                        // All-day notifications alert at 9:00 AM local time
                        if (localTime.getHour() == 9 && localTime.getMinute() == 0) {
                            matchesTime = true;
                            alertMessage = "Today: " + item.getTitle() + " (All day)";
                        }
                    } else if (item.getStartTime() != null) {
                        // Timed notifications alert at the start time
                        String startHHMM = item.getStartTime().trim();
                        if (currentHHMM.equals(startHHMM)) {
                            matchesTime = true;
                            alertMessage = item.getItemType() + ": \"" + item.getTitle() + "\" starts now at " + item.getStartTime();
                        }
                    }

                    if (matchesTime) {
                        // Create a unique occurrence push lock hash
                        String logId = sub.getId() + ":" + item.getId() + ":" + localDate;
                        if (!pushedOccurrenceLogRepository.existsById(logId)) {
                            // Lock first to prevent concurrent dispatch
                            PushedOccurrenceLog logEntry = PushedOccurrenceLog.builder()
                                    .id(logId)
                                    .taskId(item.getId())
                                    .occurrenceDate(localDate)
                                    .pushedAt(LocalDateTime.now())
                                    .build();
                            pushedOccurrenceLogRepository.save(logEntry);

                            // Send push via push service
                            log.info("Triggering background Push Alert to device {}: '{}'", sub.getId(), item.getTitle());
                            pushNotificationService.sendPush(sub, item.getTitle(), alertMessage, "/calendar");
                        }
                    }
                }
            } catch (Exception e) {
                log.error("Error running scheduler cycle for subscription: " + sub.getId(), e);
            }
        }
    }

    private boolean occursOnDate(DailyTask item, LocalDate targetDate) {
        String frequency = item.getRecurrenceFrequency() != null ? item.getRecurrenceFrequency() : "NONE";
        if ("NONE".equals(frequency)) {
            return item.getDate().equals(targetDate);
        }

        // recurrence date bounds
        if (targetDate.isBefore(item.getDate())) {
            return false;
        }
        if (item.getRecurrenceUntil() != null && targetDate.isAfter(item.getRecurrenceUntil())) {
            return false;
        }

        LocalDate cursor = item.getDate();
        while (cursor.isBefore(targetDate)) {
            cursor = switch (frequency) {
                case "DAILY" -> cursor.plusDays(1);
                case "WEEKLY" -> cursor.plusWeeks(1);
                case "MONTHLY" -> cursor.plusMonths(1);
                default -> cursor.plusYears(100);
            };
        }

        return cursor.equals(targetDate);
    }
}
