package com.personal_dashboard.backend.service;

import com.personal_dashboard.backend.dto.request.DailyTaskRequest;
import com.personal_dashboard.backend.model.DailyTask;
import com.personal_dashboard.backend.repository.DailyTaskRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class DailyTaskService {

    private final DailyTaskRepository dailyTaskRepository;

    public List<DailyTask> getTasksForDate(LocalDate date) {
        String userId = com.personal_dashboard.backend.security.UserContext.getRequiredUserId();
        return sortTasks(dailyTaskRepository.findByUserIdAndDateRange(userId, date, date.plusDays(1)).stream()
                .filter(t -> t.getItemType() == null || "TASK".equalsIgnoreCase(t.getItemType()))
                .toList());
    }

    public List<DailyTask> getTasksForDateWithIncompletePrevious(LocalDate date) {
        String userId = com.personal_dashboard.backend.security.UserContext.getRequiredUserId();
        List<DailyTask> tasks = dailyTaskRepository.findTasksForDateWithIncompletePrevious(userId, date, date.plusDays(1)).stream()
                .filter(t -> t.getItemType() == null || "TASK".equalsIgnoreCase(t.getItemType()))
                .filter(t -> t.getExcludedDates() == null || !t.getExcludedDates().contains(date))
                .map(t -> {
                    String recurrence = t.getRecurrenceFrequency() != null ? t.getRecurrenceFrequency() : "NONE";
                    if (!"NONE".equalsIgnoreCase(recurrence)) {
                        boolean isCompleted = t.getCompletedDates() != null && t.getCompletedDates().contains(date);
                        t.setCompleted(isCompleted);
                    }
                    return t;
                })
                .toList();
        return sortTasks(tasks);
    }

    public List<DailyTask> getActiveTasks() {
        LocalDateTime cutoff = LocalDateTime.now().minusHours(48);
        String userId = com.personal_dashboard.backend.security.UserContext.getRequiredUserId();
        return sortTasks(dailyTaskRepository.findActiveTasks(userId, cutoff).stream()
                .filter(t -> t.getItemType() == null || "TASK".equalsIgnoreCase(t.getItemType()))
                .toList());
    }

    public List<DailyTask> getAllTasks() {
        log.info("Fetching all tasks");
        String userId = com.personal_dashboard.backend.security.UserContext.getRequiredUserId();
        return sortTasks(dailyTaskRepository.findByUserId(userId).stream()
                .filter(t -> t.getItemType() == null || "TASK".equalsIgnoreCase(t.getItemType()))
                .toList());
    }

    public List<DailyTask> getTasksForRange(LocalDate startDate, LocalDate endDate) {
        String userId = com.personal_dashboard.backend.security.UserContext.getRequiredUserId();
        return sortTasks(dailyTaskRepository.findByUserIdAndDateRange(userId, startDate, endDate.plusDays(1)).stream()
                .filter(t -> t.getItemType() == null || "TASK".equalsIgnoreCase(t.getItemType()))
                .toList());
    }

    public DailyTask createTask(DailyTaskRequest request) {
        LocalDate taskDate = LocalDate.parse(request.getDate());
        String userId = com.personal_dashboard.backend.security.UserContext.getRequiredUserId();
        int nextOrder = dailyTaskRepository.findByUserIdAndDateRange(userId, taskDate, taskDate.plusDays(1)).size();
        boolean isCompleted = Boolean.TRUE.equals(request.getCompleted());
        String status = request.getStatus() != null ? request.getStatus() : (isCompleted ? "DONE" : "TODO");
        if (isCompleted) {
            status = "DONE";
        } else if ("DONE".equals(status)) {
            isCompleted = true;
        }
        
        DailyTask task = DailyTask.builder()
                .userId(userId)
                .title(request.getTitle())
                .date(LocalDate.parse(request.getDate()))
                .scheduledTime(request.getScheduledTime())
                .startTime(normalizeTime(request.getScheduledTime()))
                .allDay(normalizeTime(request.getScheduledTime()) == null)
                .itemType("TASK")
                .category(request.getCategory() != null ? request.getCategory() : "Personal")
                .color("#c9bff6")
                .notes(request.getNotes())
                .completed(isCompleted)
                .status(status)
                .completedAt(isCompleted ? LocalDateTime.now() : null)
                .sortOrder(request.getSortOrder() != null ? request.getSortOrder() : nextOrder)
                .recurrenceFrequency("NONE")
                .subtasks(request.getSubtasks())
                .tags(request.getTags())
                .build();
        return dailyTaskRepository.save(task);
    }

    public DailyTask updateTask(String id, DailyTaskRequest request) {
        String userId = com.personal_dashboard.backend.security.UserContext.getRequiredUserId();
        DailyTask existing = dailyTaskRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new IllegalArgumentException("Task not found with id: " + id));

        existing.setTitle(request.getTitle());
        existing.setDate(LocalDate.parse(request.getDate()));
        existing.setScheduledTime(request.getScheduledTime());
        existing.setStartTime(normalizeTime(request.getScheduledTime()));
        existing.setAllDay(normalizeTime(request.getScheduledTime()) == null);
        if (existing.getItemType() == null) {
            existing.setItemType("TASK");
        }
        if (request.getCategory() != null) {
            existing.setCategory(request.getCategory());
        }
        if (existing.getColor() == null) {
            existing.setColor("#c9bff6");
        }
        if (existing.getRecurrenceFrequency() == null) {
            existing.setRecurrenceFrequency("NONE");
        }
        existing.setNotes(request.getNotes());
        if (request.getStatus() != null) {
            existing.setStatus(request.getStatus());
            if ("DONE".equals(request.getStatus())) {
                request.setCompleted(true);
            } else {
                request.setCompleted(false);
            }
        } else if (request.getCompleted() != null) {
            existing.setStatus(Boolean.TRUE.equals(request.getCompleted()) ? "DONE" : "TODO");
        }

        if (request.getCompleted() != null) {
            boolean wasCompleted = Boolean.TRUE.equals(existing.getCompleted());
            boolean isCompleted = Boolean.TRUE.equals(request.getCompleted());
            if (isCompleted && !wasCompleted) {
                existing.setCompletedAt(LocalDateTime.now());
            } else if (!isCompleted && wasCompleted) {
                existing.setCompletedAt(null);
            }
            existing.setCompleted(request.getCompleted());
        }
        if (request.getSortOrder() != null) {
            existing.setSortOrder(request.getSortOrder());
        }
        
        if (request.getSubtasks() != null) {
            existing.setSubtasks(request.getSubtasks());
        }
        if (request.getTags() != null) {
            existing.setTags(request.getTags());
        }

        return dailyTaskRepository.save(existing);
    }

    public DailyTask toggleTask(String id) {
        return toggleTask(id, null);
    }

    public DailyTask toggleTask(String id, LocalDate occurrenceDate) {
        String userId = com.personal_dashboard.backend.security.UserContext.getRequiredUserId();
        DailyTask existing = dailyTaskRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new IllegalArgumentException("Task not found with id: " + id));
        String recurrence = existing.getRecurrenceFrequency() != null ? existing.getRecurrenceFrequency() : "NONE";

        if (occurrenceDate != null && !"NONE".equals(recurrence)) {
            List<LocalDate> completedDates = existing.getCompletedDates();
            if (completedDates == null) {
                completedDates = new java.util.ArrayList<>();
            } else {
                completedDates = new java.util.ArrayList<>(completedDates);
            }

            if (completedDates.contains(occurrenceDate)) {
                completedDates.remove(occurrenceDate);
            } else {
                completedDates.add(occurrenceDate);
            }
            existing.setCompletedDates(completedDates);
            existing.setCompleted(completedDates.contains(occurrenceDate));
            existing.setStatus(completedDates.contains(occurrenceDate) ? "DONE" : "TODO");
        } else {
            boolean newCompleted = existing.getCompleted() == null || !existing.getCompleted();
            existing.setCompleted(newCompleted);
            existing.setStatus(newCompleted ? "DONE" : "TODO");
            existing.setCompletedAt(newCompleted ? LocalDateTime.now() : null);
        }
        return dailyTaskRepository.save(existing);
    }

    public void deleteTask(String id) {
        String userId = com.personal_dashboard.backend.security.UserContext.getRequiredUserId();
        DailyTask existing = dailyTaskRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new IllegalArgumentException("Task not found with id: " + id));
        dailyTaskRepository.delete(existing);
    }

    private List<DailyTask> sortTasks(List<DailyTask> tasks) {
        return tasks.stream()
                .peek(t -> {
                    if (Boolean.TRUE.equals(t.getCompleted()) && "TODO".equals(t.getStatus())) {
                        t.setStatus("DONE");
                    }
                })
                .sorted(Comparator
                        .comparing(DailyTask::getDate, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(DailyTask::getSortOrder, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(DailyTask::getScheduledTime, Comparator.nullsLast(String::compareToIgnoreCase))
                        .thenComparing(DailyTask::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }

    private String normalizeTime(String scheduledTime) {
        if (scheduledTime == null || scheduledTime.isBlank()) {
            return null;
        }
        String trimmed = scheduledTime.trim();
        if (trimmed.matches("\\d{2}:\\d{2}")) {
            return trimmed;
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("^(\\d{1,2}):(\\d{2})\\s*(AM|PM)$", java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(trimmed);
        if (!matcher.matches()) {
            return trimmed;
        }
        int hour = Integer.parseInt(matcher.group(1));
        String minute = matcher.group(2);
        String period = matcher.group(3).toUpperCase();
        if ("PM".equals(period) && hour < 12) {
            hour += 12;
        }
        if ("AM".equals(period) && hour == 12) {
            hour = 0;
        }
        return String.format("%02d:%s", hour, minute);
    }
}
