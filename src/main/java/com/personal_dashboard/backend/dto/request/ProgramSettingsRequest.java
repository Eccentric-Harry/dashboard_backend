package com.personal_dashboard.backend.dto.request;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * PUT /program/{id} — everything about a program except its targets, which only the weekly
 * review may change. Null fields are left as they are. Moving the start date moves the end
 * date with it, so the program keeps its length.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProgramSettingsRequest {

    @Size(max = 40, message = "title must be at most 40 characters")
    private String title;

    @Pattern(regexp = "(\\d{4}-\\d{2}-\\d{2})?", message = "startDate must be yyyy-MM-dd")
    private String startDate;

    @Pattern(regexp = "(\\d{4}-\\d{2}-\\d{2})?", message = "birthday must be yyyy-MM-dd")
    private String birthday;

    @DecimalMin(value = "30", message = "weight must be at least 30 kg")
    @DecimalMax(value = "250", message = "weight must be at most 250 kg")
    private Double weightKg;

    @Pattern(regexp = "^(gym|home)?$", message = "liftPlace must be gym or home")
    private String liftPlace;

    @Size(max = 8, message = "at most 8 answers")
    private Map<
            @Pattern(regexp = "^[a-z]{1,20}$", message = "answer keys must be short lowercase keys") String,
            @Size(max = 200, message = "each answer must be at most 200 characters") String> answers;

    /** Track key → if-then plan; a blank plan clears it. */
    @Size(max = 8, message = "at most 8 plans")
    private Map<
            @Pattern(regexp = "^[a-z]{1,12}$", message = "plan keys must be track keys") String,
            @Size(max = 200, message = "each plan must be at most 200 characters") String> plans;

    /**
     * Track key → the date it starts, for a track started before its scheduled day; a blank
     * date puts it back on the schedule. A date before day 1 means day 1.
     */
    @Size(max = 8, message = "at most 8 tracks")
    private Map<
            @Pattern(regexp = "^[a-z]{1,12}$", message = "track keys must be track keys") String,
            @Pattern(regexp = "(\\d{4}-\\d{2}-\\d{2})?", message = "each date must be yyyy-MM-dd") String> opens;
}
