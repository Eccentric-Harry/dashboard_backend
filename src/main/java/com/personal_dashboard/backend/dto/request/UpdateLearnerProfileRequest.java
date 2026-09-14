package com.personal_dashboard.backend.dto.request;

import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class UpdateLearnerProfileRequest {

    /** Blank clears it. */
    @Size(max = 2000, message = "Learner profile must be at most 2000 characters")
    private String learnerProfile;
}
