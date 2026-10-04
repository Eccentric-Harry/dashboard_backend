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
public class CampClaimRequest {

    /** The quest's id, "yyyy-MM-dd:slot" — today's or yesterday's. */
    @NotBlank(message = "questId is required")
    @Pattern(regexp = "\\d{4}-\\d{2}-\\d{2}:[0-2]", message = "questId must be yyyy-MM-dd:slot")
    private String questId;
}
