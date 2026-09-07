package com.personal_dashboard.backend.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Triage an entry into one of the three handling lanes, answering "what is this?"
 * before any action is offered on it.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MindLaneRequest {

    // PROBLEM | WORRY | INTRUSIVE
    @NotBlank(message = "Lane is required")
    private String lane;
}
