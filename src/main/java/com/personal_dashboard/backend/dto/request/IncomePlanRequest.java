package com.personal_dashboard.backend.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * The user's pay, for savings-goal planning. Both fields replace the stored value; null
 * clears it (the plan then says "add your take-home" instead of guessing).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IncomePlanRequest {

    @DecimalMin(value = "0.0", message = "takeHomeMonthly cannot be negative")
    private BigDecimal takeHomeMonthly;

    @Min(value = 1, message = "payday must be between 1 and 31")
    @Max(value = 31, message = "payday must be between 1 and 31")
    private Integer payday;
}
