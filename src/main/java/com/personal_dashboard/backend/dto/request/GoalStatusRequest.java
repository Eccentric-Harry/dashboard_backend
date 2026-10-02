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
public class GoalStatusRequest {

    @NotBlank(message = "status is required")
    @Pattern(regexp = "^(ACTIVE|ARCHIVED)$", message = "status must be ACTIVE or ARCHIVED")
    private String status;
}
