package com.personal_dashboard.backend.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** One action on a program track (see ProgramLog). Only the fields the track uses are set. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProgramLogRequest {

    @NotBlank(message = "track is required")
    @Pattern(regexp = "^(run|lift|protein|mood|learn|english|screen|regard)$", message = "track must be a program track")
    private String track;

    @NotBlank(message = "date is required")
    @Pattern(regexp = "\\d{4}-\\d{2}-\\d{2}", message = "date must be yyyy-MM-dd")
    private String date;

    @Pattern(regexp = "^(FULL|MIN|REST)?$", message = "level must be FULL, MIN or REST")
    private String level;

    @DecimalMin(value = "0", message = "value can't be negative")
    @DecimalMax(value = "100000", message = "value is too large")
    private Double value;

    @Min(value = 0, message = "minutes can't be negative")
    @Max(value = 600, message = "minutes must be at most 600")
    private Integer minutes;

    @DecimalMin(value = "0", message = "distance can't be negative")
    @DecimalMax(value = "200", message = "distance must be at most 200 km")
    private Double distanceKm;

    @Min(value = 1, message = "feel is 1–5")
    @Max(value = 5, message = "feel is 1–5")
    private Integer feel;

    @Size(max = 12, message = "at most 12 exercises")
    private List<@Valid @NotNull SetRequest> sets;

    @Size(max = 30, message = "tag must be at most 30 characters")
    private String tag;

    @Size(max = 280, message = "note must be at most 280 characters")
    private String note;

    @Size(max = 280, message = "text must be at most 280 characters")
    private String text;

    @Size(max = 280, message = "kind must be at most 280 characters")
    private String kind;

    @Size(max = 64, message = "pursuitId is too long")
    private String pursuitId;

    @Pattern(regexp = "^[a-z0-9-]{0,40}$", message = "session must be a short lowercase key")
    private String session;

    private Boolean urge;

    private Boolean morningRule;

    private Boolean nightRule;

    private Boolean stretch;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SetRequest {
        @NotBlank(message = "exercise is required")
        @Size(max = 40, message = "exercise must be at most 40 characters")
        private String exercise;

        @DecimalMin(value = "0", message = "weight can't be negative")
        @DecimalMax(value = "500", message = "weight must be at most 500 kg")
        private Double weightKg;

        @Min(value = 0, message = "reps can't be negative")
        @Max(value = 200, message = "reps must be at most 200")
        private Integer reps;
    }
}
