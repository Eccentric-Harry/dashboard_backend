package com.personal_dashboard.backend.dto.request;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Create or edit a goal. Shape rules that span fields (CHECK + DAY is always 1, CHECK +
 * WEEK is 1–7 days) are enforced in GoalService, which normalises rather than rejects
 * where the intent is unambiguous.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GoalRequest {

    @NotBlank(message = "title is required")
    @Size(max = 60, message = "title must be at most 60 characters")
    private String title;

    @Pattern(regexp = "^[a-z-]{0,24}$", message = "icon must be a short lowercase key")
    private String icon;

    @Pattern(regexp = "^(tangerine|mint|sky|grape|berry|lemon|teal)?$", message = "color must be one of the goal palette keys")
    private String color;

    /** The goal's own world: "path" (The Quiet Path), or blank for camp only. */
    @Pattern(regexp = "^(path)?$", message = "world must be a known goal world")
    private String world;

    @NotBlank(message = "measure is required")
    @Pattern(regexp = "^(COUNT|CHECK)$", message = "measure must be COUNT or CHECK")
    private String measure;

    @NotBlank(message = "period is required")
    @Pattern(regexp = "^(DAY|WEEK)$", message = "period must be DAY or WEEK")
    private String period;

    @NotNull(message = "target is required")
    @Positive(message = "target must be above zero")
    @DecimalMax(value = "100000", message = "target is too large")
    private Double target;

    @Size(max = 16, message = "unit must be at most 16 characters")
    private String unit;

    @Min(value = 1, message = "daysPerWeek must be between 1 and 7")
    @Max(value = 7, message = "daysPerWeek must be between 1 and 7")
    private Integer daysPerWeek;

    /** yyyy-MM-dd; defaults to today. Never in the future. */
    @Pattern(regexp = "\\d{4}-\\d{2}-\\d{2}", message = "startDate must be yyyy-MM-dd")
    private String startDate;
}
