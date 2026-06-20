package com.personal_dashboard.backend.dto;

import com.personal_dashboard.backend.model.DailyTask;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.Instant;
import java.util.List;
import com.personal_dashboard.backend.model.TaskHistoryEvent;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CalendarItemOccurrence {

    private String id;
    private String occurrenceId;
    private LocalDate date;
    private LocalDate originalDate;
    private String title;
    private String startTime;
    private String endTime;
    private Boolean allDay;
    private String itemType;
    private String category;
    private String color;
    private String notes;
    private Boolean completed;
    private Boolean cancelled;
    private Integer sortOrder;
    private String recurrenceFrequency;
    private LocalDate recurrenceUntil;
    private List<TaskHistoryEvent> history;
    private Instant createdAt;

    public static CalendarItemOccurrence from(DailyTask item, LocalDate occurrenceDate) {
        boolean isCompleted;
        boolean isCancelled = false;
        if (item.getRecurrenceFrequency() != null && !"NONE".equals(item.getRecurrenceFrequency())) {
            isCompleted = item.getCompletedDates() != null && item.getCompletedDates().contains(occurrenceDate);
            isCancelled = item.getCancelledDates() != null && item.getCancelledDates().contains(occurrenceDate);
        } else {
            isCompleted = Boolean.TRUE.equals(item.getCompleted());
            isCancelled = Boolean.TRUE.equals(item.getCancelled());
        }

        return CalendarItemOccurrence.builder()
                .id(item.getId())
                .occurrenceId(item.getId() + ":" + occurrenceDate)
                .date(occurrenceDate)
                .originalDate(item.getDate())
                .title(item.getTitle())
                .startTime(item.getStartTime() != null ? item.getStartTime()
                        : normalizeLegacyTime(item.getScheduledTime()))
                .endTime(item.getEndTime())
                .allDay(item.getAllDay() != null ? item.getAllDay() : item.getScheduledTime() == null)
                .itemType(item.getItemType() != null ? item.getItemType() : "TASK")
                .category(item.getCategory() != null ? item.getCategory() : "Personal")
                .color(item.getColor())
                .notes(item.getNotes())
                .completed(isCompleted)
                .cancelled(isCancelled)
                .sortOrder(item.getSortOrder())
                .recurrenceFrequency(item.getRecurrenceFrequency() != null ? item.getRecurrenceFrequency() : "NONE")
                .recurrenceUntil(item.getRecurrenceUntil())
                .history(item.getHistory())
                .createdAt(item.getCreatedAt())
                .build();
    }

    private static String normalizeLegacyTime(String scheduledTime) {
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
