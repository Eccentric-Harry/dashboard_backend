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
        return sortTasks(dailyTaskRepository.findByDateRange(date, date.plusDays(1)).stream()
                .filter(t -> t.getItemType() == null || "TASK".equalsIgnoreCase(t.getItemType()))
                .toList());
    }

    public List<DailyTask> getTasksForDateWithIncompletePrevious(LocalDate date) {
        List<DailyTask> tasks = dailyTaskRepository.findTasksForDateWithIncompletePrevious(date, date.plusDays(1)).stream()
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
        return sortTasks(dailyTaskRepository.findActiveTasks(cutoff).stream()
                .filter(t -> t.getItemType() == null || "TASK".equalsIgnoreCase(t.getItemType()))
                .toList());
    }

    public List<DailyTask> getAllTasks() {
        log.info("Fetching all tasks");
        return sortTasks(dailyTaskRepository.findAll().stream()
                .filter(t -> t.getItemType() == null || "TASK".equalsIgnoreCase(t.getItemType()))
                .toList());
    }

    public List<DailyTask> getTasksForRange(LocalDate startDate, LocalDate endDate) {
        return sortTasks(dailyTaskRepository.findByDateRange(startDate, endDate.plusDays(1)).stream()
                .filter(t -> t.getItemType() == null || "TASK".equalsIgnoreCase(t.getItemType()))
                .toList());
    }

    public DailyTask createTask(DailyTaskRequest request) {
        LocalDate taskDate = LocalDate.parse(request.getDate());
        int nextOrder = dailyTaskRepository.findByDateRange(taskDate, taskDate.plusDays(1)).size();
        boolean isCompleted = Boolean.TRUE.equals(request.getCompleted());
        DailyTask task = DailyTask.builder()
                .title(request.getTitle())
                .date(LocalDate.parse(request.getDate()))
                .scheduledTime(request.getScheduledTime())
                .startTime(normalizeTime(request.getScheduledTime()))
                .allDay(normalizeTime(request.getScheduledTime()) == null)
                .itemType("TASK")
                .category("Learning")
                .color("#c9bff6")
                .notes(request.getNotes())
                .completed(request.getCompleted() != null ? request.getCompleted() : false)
                .completedAt(isCompleted ? LocalDateTime.now() : null)
                .sortOrder(request.getSortOrder() != null ? request.getSortOrder() : nextOrder)
                .recurrenceFrequency("NONE")
                .build();
        return dailyTaskRepository.save(task);
    }

    public DailyTask updateTask(String id, DailyTaskRequest request) {
        DailyTask existing = dailyTaskRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Task not found with id: " + id));

        existing.setTitle(request.getTitle());
        existing.setDate(LocalDate.parse(request.getDate()));
        existing.setScheduledTime(request.getScheduledTime());
        existing.setStartTime(normalizeTime(request.getScheduledTime()));
        existing.setAllDay(normalizeTime(request.getScheduledTime()) == null);
        if (existing.getItemType() == null) {
            existing.setItemType("TASK");
        }
        if (existing.getCategory() == null) {
            existing.setCategory("Learning");
        }
        if (existing.getColor() == null) {
            existing.setColor("#c9bff6");
        }
        if (existing.getRecurrenceFrequency() == null) {
            existing.setRecurrenceFrequency("NONE");
        }
        existing.setNotes(request.getNotes());
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

        return dailyTaskRepository.save(existing);
    }

    public DailyTask toggleTask(String id) {
        return toggleTask(id, null);
    }

    public DailyTask toggleTask(String id, LocalDate occurrenceDate) {
        DailyTask existing = dailyTaskRepository.findById(id)
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
        } else {
            boolean newCompleted = existing.getCompleted() == null || !existing.getCompleted();
            existing.setCompleted(newCompleted);
            existing.setCompletedAt(newCompleted ? LocalDateTime.now() : null);
        }
        return dailyTaskRepository.save(existing);
    }

    public void deleteTask(String id) {
        if (!dailyTaskRepository.existsById(id)) {
            throw new IllegalArgumentException("Task not found with id: " + id);
        }
        dailyTaskRepository.deleteById(id);
    }

    private List<DailyTask> sortTasks(List<DailyTask> tasks) {
        return tasks.stream()
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
