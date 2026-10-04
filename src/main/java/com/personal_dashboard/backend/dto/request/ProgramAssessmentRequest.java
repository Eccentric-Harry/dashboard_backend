package com.personal_dashboard.backend.dto.request;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** POST /program/{id}/assessments — answers are scored server-side (util/ProgramScoring). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProgramAssessmentRequest {

    @NotBlank(message = "type is required")
    @Pattern(regexp = "^(ROSENBERG|WHO5|BODY)$", message = "type must be ROSENBERG, WHO5 or BODY")
    private String type;

    @NotBlank(message = "date is required")
    @Pattern(regexp = "\\d{4}-\\d{2}-\\d{2}", message = "date must be yyyy-MM-dd")
    private String date;

    @Size(max = 10, message = "at most 10 answers")
    private List<@NotNull Integer> answers;

    @DecimalMin(value = "25", message = "weight must be at least 25 kg")
    @DecimalMax(value = "300", message = "weight must be at most 300 kg")
    private Double weightKg;

    @DecimalMin(value = "30", message = "waist must be at least 30 cm")
    @DecimalMax(value = "250", message = "waist must be at most 250 cm")
    private Double waistCm;

    @Size(max = 4, message = "at most 4 photos")
    private List<@NotBlank @Size(max = 64) String> mediaIds;

    @Size(max = 300, message = "note must be at most 300 characters")
    private String note;
}
