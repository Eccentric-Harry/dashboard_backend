package com.personal_dashboard.backend.dto.request;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/** POST /program — begins a program (see Program). Dates are the user's local days. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProgramStartRequest {

    @Size(max = 40, message = "title must be at most 40 characters")
    private String title;

    @NotBlank(message = "startDate is required")
    @Pattern(regexp = "\\d{4}-\\d{2}-\\d{2}", message = "startDate must be yyyy-MM-dd")
    private String startDate;

    /** Inclusive; defaults to 89 days after the start (90 days). */
    @Pattern(regexp = "(\\d{4}-\\d{2}-\\d{2})?", message = "endDate must be yyyy-MM-dd")
    private String endDate;

    /** When sealed letters open; defaults to the end date. */
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
}
