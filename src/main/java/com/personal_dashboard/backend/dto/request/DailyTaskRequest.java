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
public class DailyTaskRequest {

    @NotBlank(message = "Title is required")
    private String title;

    /**
     * Not required at the DTO level: an edit that doesn't touch the date (e.g. toggling a
     * tag or a subtask on an already-overdue task) must not be forced to resend one just to
     * pass validation. {@link DailyTaskService#updateTask} keeps the task's existing date
     * when this is blank; {@link DailyTaskService#createTask} still requires one explicitly,
     * since a brand-new task has no existing date to fall back to.
     */
    @Pattern(regexp = "^$|\\d{4}-\\d{2}-\\d{2}", message = "Date must be in format YYYY-MM-DD")
    private String date;

    private String scheduledTime;
    private String notes;
    private Boolean completed;
    private String status;
    private Integer sortOrder;
    private String category;
    
    private java.util.List<com.personal_dashboard.backend.model.SubTask> subtasks;
    private java.util.List<String> tags;
}
