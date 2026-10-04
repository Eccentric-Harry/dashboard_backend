package com.personal_dashboard.backend.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** POST /program/{id}/media — a progress photo or a speaking recording, as a data: URL. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProgramMediaRequest {

    @NotBlank(message = "kind is required")
    @Pattern(regexp = "^(PHOTO|AUDIO)$", message = "kind must be PHOTO or AUDIO")
    private String kind;

    @NotBlank(message = "label is required")
    @Pattern(regexp = "^(front|side|speech)$", message = "label must be front, side or speech")
    private String label;

    @NotBlank(message = "date is required")
    @Pattern(regexp = "\\d{4}-\\d{2}-\\d{2}", message = "date must be yyyy-MM-dd")
    private String date;

    /** data:<mime>;base64,<bytes>. Size limits per kind are checked by the service. */
    @NotBlank(message = "dataUrl is required")
    @Size(max = 6_000_000, message = "that file is too large")
    private String dataUrl;

    @Min(value = 0, message = "duration can't be negative")
    @Max(value = 600, message = "recordings are at most 10 minutes")
    private Integer durationSec;
}
