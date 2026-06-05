package com.personal_dashboard.backend.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CalendarItemRequest {

    @NotBlank(message = "Title is required")
    private String title;

    @NotBlank(message = "Date is required")
    @Pattern(regexp = "\\d{4}-\\d{2}-\\d{2}", message = "Date must be in format YYYY-MM-DD")
    private String date;

    @Pattern(regexp = "^$|\\d{2}:\\d{2}", message = "Start time must be in format HH:mm")
    private String startTime;

    @Pattern(regexp = "^$|\\d{2}:\\d{2}", message = "End time must be in format HH:mm")
    private String endTime;

    private Boolean allDay;
    private String itemType;
    private String category;
    private String color;
    private String notes;
    private Boolean completed;
    private Integer sortOrder;

    @Pattern(regexp = "NONE|DAILY|WEEKLY|MONTHLY", message = "Recurrence must be NONE, DAILY, WEEKLY, or MONTHLY")
    private String recurrenceFrequency;

    @Pattern(regexp = "^$|\\d{4}-\\d{2}-\\d{2}", message = "Repeat-until date must be in format YYYY-MM-DD")
    private String recurrenceUntil;
}
