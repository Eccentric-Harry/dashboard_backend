package com.personal_dashboard.backend.service;

import com.personal_dashboard.backend.dto.CalendarItemOccurrence;
import com.personal_dashboard.backend.dto.request.CalendarItemRequest;
import com.personal_dashboard.backend.model.DailyTask;
import com.personal_dashboard.backend.repository.DailyTaskRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Comparator;
import java.util.List;
import java.time.Instant;
import com.personal_dashboard.backend.model.TaskHistoryEvent;
import java.util.Locale;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class CalendarItemService {

    private static final Map<String, String> CATEGORY_COLORS = Map.of(
            "WORK", "#2563eb",
            "PERSONAL", "#7c3aed",
            "HEALTH", "#10b981",
            "LEARNING", "#0d9488",
            "FINANCE", "#d97706",
            "SOCIAL", "#db2777"
    );

    private final DailyTaskRepository dailyTaskRepository;

    public List<CalendarItemOccurrence> getOccurrences(LocalDate startDate, LocalDate endDate) {
        if (endDate.isBefore(startDate)) {
            throw new IllegalArgumentException("End date must be on or after start date");
        }
        LocalDate endExclusive = endDate.plusDays(1);
        String userId = com.personal_dashboard.backend.security.UserContext.getRequiredUserId();
        return dailyTaskRepository.findCalendarCandidates(userId, startDate, endExclusive).stream()
                .flatMap(item -> expandItem(item, startDate, endDate).stream())
                .sorted(Comparator
                        .comparing(CalendarItemOccurrence::getDate)
                        .thenComparing(item -> Boolean.TRUE.equals(item.getAllDay()) ? 0 : 1)
                        .thenComparing(CalendarItemOccurrence::getStartTime, Comparator.nullsLast(String::compareTo))
                        .thenComparing(CalendarItemOccurrence::getSortOrder, Comparator.nullsLast(Integer::compareTo))
                        .thenComparing(CalendarItemOccurrence::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }

    public DailyTask getItem(String id) {
        String userId = com.personal_dashboard.backend.security.UserContext.getRequiredUserId();
        return dailyTaskRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new IllegalArgumentException("Calendar item not found with id: " + id));
    }

    public DailyTask createItem(CalendarItemRequest request) {
        LocalDate date = LocalDate.parse(request.getDate());
        validateRequest(request);
        String userId = com.personal_dashboard.backend.security.UserContext.getRequiredUserId();
        int nextOrder = dailyTaskRepository.findByUserIdAndDateRange(userId, date, date.plusDays(1)).size();
        boolean completed = Boolean.TRUE.equals(request.getCompleted());
        boolean cancelled = Boolean.TRUE.equals(request.getCancelled());
        String recurrence = defaultText(request.getRecurrenceFrequency(), "NONE").toUpperCase(Locale.ROOT);

        List<LocalDate> completedDates = null;
        List<LocalDate> cancelledDates = null;
        boolean parentCompleted = false;
        boolean parentCancelled = false;
        LocalDateTime completedAt = null;

        if (!"NONE".equals(recurrence)) {
            completedDates = new java.util.ArrayList<>();
            cancelledDates = new java.util.ArrayList<>();
            if (completed) {
                completedDates.add(date);
            }
            if (cancelled) {
                cancelledDates.add(date);
            }
        } else {
            parentCompleted = completed;
            parentCancelled = cancelled;
            completedAt = completed ? LocalDateTime.now() : null;
        }

        DailyTask item = DailyTask.builder()
                .userId(userId)
                .title(request.getTitle().trim())
                .date(date)
                .scheduledTime(blankToNull(request.getStartTime()))
                .startTime(blankToNull(request.getStartTime()))
                .endTime(blankToNull(request.getEndTime()))
                .allDay(request.getAllDay() != null ? request.getAllDay() : blankToNull(request.getStartTime()) == null)
                .itemType(defaultText(request.getItemType(), "TASK").toUpperCase(Locale.ROOT))
                .category(defaultText(request.getCategory(), "Personal"))
                .color(defaultColor(request.getCategory(), request.getColor()))
                .notes(blankToNull(request.getNotes()))
                .completed(parentCompleted)
                .cancelled(parentCancelled)
                .completedAt(completedAt)
                .completedDates(completedDates)
                .cancelledDates(cancelledDates)
                .sortOrder(request.getSortOrder() != null ? request.getSortOrder() : nextOrder)
                .recurrenceFrequency(recurrence)
                .recurrenceUntil(parseOptionalDate(request.getRecurrenceUntil()))
                .origin(com.personal_dashboard.backend.model.EventOrigin.local())
                .build();
        return dailyTaskRepository.save(item);
    }

    public DailyTask updateItem(String id, CalendarItemRequest request) {
        validateRequest(request);
        DailyTask existing = getItem(id);
        String recurrence = defaultText(request.getRecurrenceFrequency(), "NONE").toUpperCase(Locale.ROOT);

        List<TaskHistoryEvent> history = existing.getHistory();
        if (history == null) {
            history = new java.util.ArrayList<>();
        } else {
            history = new java.util.ArrayList<>(history);
        }

        String newTitle = request.getTitle().trim();
        if (!newTitle.equals(existing.getTitle())) {
            history.add(TaskHistoryEvent.builder()
                    .timestamp(Instant.now())
                    .message("Task title updated from \"" + existing.getTitle() + "\" to \"" + newTitle + "\"")
                    .build());
            existing.setTitle(newTitle);
        }

        LocalDate newDate = LocalDate.parse(request.getDate());
        if (existing.getDate() != null && !newDate.equals(existing.getDate())) {
            history.add(TaskHistoryEvent.builder()
                    .timestamp(Instant.now())
                    .message("Moved from " + existing.getDate() + " to " + newDate)
                    .build());
        }
        existing.setDate(newDate);

        existing.setHistory(history);
        existing.setScheduledTime(blankToNull(request.getStartTime()));
        existing.setStartTime(blankToNull(request.getStartTime()));
        existing.setEndTime(blankToNull(request.getEndTime()));
        existing.setAllDay(request.getAllDay() != null ? request.getAllDay() : blankToNull(request.getStartTime()) == null);
        existing.setItemType(defaultText(request.getItemType(), "TASK").toUpperCase(Locale.ROOT));
        existing.setCategory(defaultText(request.getCategory(), "Personal"));
        existing.setColor(defaultColor(request.getCategory(), request.getColor()));
        existing.setNotes(blankToNull(request.getNotes()));

        if (!"NONE".equals(recurrence)) {
            LocalDate occurrenceDate = LocalDate.parse(request.getDate());
            List<LocalDate> completedDates = existing.getCompletedDates();
            if (completedDates == null) {
                completedDates = new java.util.ArrayList<>();
            } else {
                completedDates = new java.util.ArrayList<>(completedDates);
            }
            boolean requestedCompleted = Boolean.TRUE.equals(request.getCompleted());
            if (requestedCompleted) {
                if (!completedDates.contains(occurrenceDate)) {
                    completedDates.add(occurrenceDate);
                    history.add(TaskHistoryEvent.builder().timestamp(Instant.now()).message("Marked occurrence on " + occurrenceDate + " as completed").build());
                }
            } else {
                if (completedDates.contains(occurrenceDate)) {
                    completedDates.remove(occurrenceDate);
                    history.add(TaskHistoryEvent.builder().timestamp(Instant.now()).message("Marked occurrence on " + occurrenceDate + " as incomplete").build());
                }
            }
            existing.setCompletedDates(completedDates);
            existing.setCompleted(false);
            existing.setCompletedAt(null);

            List<LocalDate> cancelledDates = existing.getCancelledDates();
            if (cancelledDates == null) {
                cancelledDates = new java.util.ArrayList<>();
            } else {
                cancelledDates = new java.util.ArrayList<>(cancelledDates);
            }
            boolean requestedCancelled = Boolean.TRUE.equals(request.getCancelled());
            if (requestedCancelled) {
                if (!cancelledDates.contains(occurrenceDate)) {
                    cancelledDates.add(occurrenceDate);
                }
            } else {
                cancelledDates.remove(occurrenceDate);
            }
            existing.setCancelledDates(cancelledDates);
            existing.setCancelled(false);

        } else {
            existing.setCompletedDates(null);
            boolean wasCompleted = Boolean.TRUE.equals(existing.getCompleted());
            boolean completed = request.getCompleted() != null ? request.getCompleted() : wasCompleted;
            existing.setCompleted(completed);
            if (completed && !wasCompleted) {
                existing.setCompletedAt(LocalDateTime.now());
                history.add(TaskHistoryEvent.builder().timestamp(Instant.now()).message("Marked as completed").build());
            } else if (!completed && wasCompleted) {
                existing.setCompletedAt(null);
                history.add(TaskHistoryEvent.builder().timestamp(Instant.now()).message("Marked as incomplete").build());
            }

            existing.setCancelledDates(null);
            boolean wasCancelled = Boolean.TRUE.equals(existing.getCancelled());
            boolean cancelled = request.getCancelled() != null ? request.getCancelled() : wasCancelled;
            existing.setCancelled(cancelled);
        }

        if (request.getSortOrder() != null) {
            existing.setSortOrder(request.getSortOrder());
        }
        existing.setRecurrenceFrequency(recurrence);
        existing.setRecurrenceUntil(parseOptionalDate(request.getRecurrenceUntil()));
        return dailyTaskRepository.save(existing);
    }

    public DailyTask toggleItem(String id) {
        return toggleItem(id, null);
    }

    public DailyTask toggleItem(String id, LocalDate occurrenceDate) {
        DailyTask existing = getItem(id);
        String recurrence = existing.getRecurrenceFrequency() != null ? existing.getRecurrenceFrequency() : "NONE";

        List<TaskHistoryEvent> history = existing.getHistory();
        if (history == null) {
            history = new java.util.ArrayList<>();
        } else {
            history = new java.util.ArrayList<>(history);
        }

        if (occurrenceDate != null && !"NONE".equals(recurrence)) {
            List<LocalDate> completedDates = existing.getCompletedDates();
            if (completedDates == null) {
                completedDates = new java.util.ArrayList<>();
            } else {
                completedDates = new java.util.ArrayList<>(completedDates);
            }

            if (completedDates.contains(occurrenceDate)) {
                completedDates.remove(occurrenceDate);
                history.add(TaskHistoryEvent.builder().timestamp(Instant.now()).message("Marked occurrence on " + occurrenceDate + " as incomplete").build());
            } else {
                completedDates.add(occurrenceDate);
                history.add(TaskHistoryEvent.builder().timestamp(Instant.now()).message("Marked occurrence on " + occurrenceDate + " as completed").build());
            }
            existing.setCompletedDates(completedDates);
        } else {
            boolean completed = existing.getCompleted() == null || !existing.getCompleted();
            existing.setCompleted(completed);
            existing.setCompletedAt(completed ? LocalDateTime.now() : null);
            if (completed) {
                history.add(TaskHistoryEvent.builder().timestamp(Instant.now()).message("Marked as completed").build());
            } else {
                history.add(TaskHistoryEvent.builder().timestamp(Instant.now()).message("Marked as incomplete").build());
            }
        }
        existing.setHistory(history);
        return dailyTaskRepository.save(existing);
    }

    public DailyTask toggleCancelItem(String id) {
        return toggleCancelItem(id, null);
    }

    public DailyTask toggleCancelItem(String id, LocalDate occurrenceDate) {
        DailyTask existing = getItem(id);
        String recurrence = existing.getRecurrenceFrequency() != null ? existing.getRecurrenceFrequency() : "NONE";

        if (occurrenceDate != null && !"NONE".equals(recurrence)) {
            List<LocalDate> cancelledDates = existing.getCancelledDates();
            if (cancelledDates == null) {
                cancelledDates = new java.util.ArrayList<>();
            } else {
                cancelledDates = new java.util.ArrayList<>(cancelledDates);
            }

            if (cancelledDates.contains(occurrenceDate)) {
                cancelledDates.remove(occurrenceDate);
            } else {
                cancelledDates.add(occurrenceDate);
            }
            existing.setCancelledDates(cancelledDates);
        } else {
            boolean cancelled = existing.getCancelled() == null || !existing.getCancelled();
            existing.setCancelled(cancelled);
        }
        return dailyTaskRepository.save(existing);
    }

    public void deleteItem(String id) {
        String userId = com.personal_dashboard.backend.security.UserContext.getRequiredUserId();
        DailyTask existing = dailyTaskRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new IllegalArgumentException("Calendar item not found with id: " + id));
        dailyTaskRepository.delete(existing);
    }

    public void deleteOccurrence(String id, LocalDate date) {
        DailyTask existing = getItem(id);
        List<LocalDate> excludedDates = existing.getExcludedDates();
        if (excludedDates == null) {
            excludedDates = new java.util.ArrayList<>();
        } else {
            excludedDates = new java.util.ArrayList<>(excludedDates);
        }
        if (!excludedDates.contains(date)) {
            excludedDates.add(date);
        }
        existing.setExcludedDates(excludedDates);
        dailyTaskRepository.save(existing);
    }

    private List<CalendarItemOccurrence> expandItem(DailyTask item, LocalDate startDate, LocalDate endDate) {
        String recurrence = defaultText(item.getRecurrenceFrequency(), "NONE").toUpperCase(Locale.ROOT);
        if ("NONE".equals(recurrence)) {
            return !item.getDate().isBefore(startDate) && !item.getDate().isAfter(endDate)
                    ? List.of(CalendarItemOccurrence.from(item, item.getDate()))
                    : List.of();
        }

        LocalDate recurrenceEnd = item.getRecurrenceUntil() != null && item.getRecurrenceUntil().isBefore(endDate)
                ? item.getRecurrenceUntil()
                : endDate;
        if (recurrenceEnd.isBefore(startDate)) {
            return List.of();
        }

        java.util.ArrayList<CalendarItemOccurrence> occurrences = new java.util.ArrayList<>();
        LocalDate cursor = item.getDate();
        int guard = 0;
        while (cursor.isBefore(startDate) && guard++ < 5000) {
            cursor = nextOccurrence(cursor, recurrence);
        }
        while (!cursor.isAfter(recurrenceEnd) && guard++ < 5000) {
            if (!cursor.isBefore(startDate)) {
                if (item.getExcludedDates() == null || !item.getExcludedDates().contains(cursor)) {
                    occurrences.add(CalendarItemOccurrence.from(item, cursor));
                }
            }
            cursor = nextOccurrence(cursor, recurrence);
        }
        return occurrences;
    }

    private LocalDate nextOccurrence(LocalDate current, String recurrence) {
        return switch (recurrence) {
            case "DAILY" -> current.plusDays(1);
            case "WEEKLY" -> current.plusWeeks(1);
            case "MONTHLY" -> current.plusMonths(1);
            default -> current.plusYears(100);
        };
    }

    private void validateRequest(CalendarItemRequest request) {
        boolean allDay = Boolean.TRUE.equals(request.getAllDay());
        String startTime = blankToNull(request.getStartTime());
        String endTime = blankToNull(request.getEndTime());
        if (!allDay && startTime == null) {
            throw new IllegalArgumentException("Start time is required for timed calendar items");
        }
        if (startTime != null) {
            LocalTime start = LocalTime.parse(startTime);
            if (endTime != null && !LocalTime.parse(endTime).isAfter(start)) {
                throw new IllegalArgumentException("End time must be after start time");
            }
        }
        LocalDate date = LocalDate.parse(request.getDate());
        LocalDate recurrenceUntil = parseOptionalDate(request.getRecurrenceUntil());
        if (recurrenceUntil != null && recurrenceUntil.isBefore(date)) {
            throw new IllegalArgumentException("Repeat-until date must be on or after the start date");
        }
    }

    private LocalDate parseOptionalDate(String value) {
        String normalized = blankToNull(value);
        return normalized == null ? null : LocalDate.parse(normalized);
    }

    private String defaultColor(String category, String color) {
        String explicit = blankToNull(color);
        if (explicit != null) {
            return explicit;
        }
        return CATEGORY_COLORS.getOrDefault(defaultText(category, "Personal").toUpperCase(Locale.ROOT), "#7c3aed");
    }

    private String defaultText(String value, String fallback) {
        String normalized = blankToNull(value);
        return normalized == null ? fallback : normalized;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
