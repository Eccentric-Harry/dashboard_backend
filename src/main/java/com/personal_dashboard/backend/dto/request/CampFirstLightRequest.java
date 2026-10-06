package com.personal_dashboard.backend.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Hoot's "first light": which goal to light first on {@code date}. A null goalId clears the pick. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CampFirstLightRequest {

    /** Today or tomorrow, yyyy-MM-dd. */
    @NotNull(message = "date is required")
    @Pattern(regexp = "^\\d{4}-\\d{2}-\\d{2}$", message = "date must be yyyy-MM-dd")
    private String date;

    @Size(max = 64, message = "that goal id is too long")
    private String goalId;
}
