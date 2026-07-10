package com.personal_dashboard.backend.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/** Sets the user's monthly spending budget to an absolute value. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BudgetUpdateRequest {

    @NotNull(message = "monthlyBudget is required")
    @Positive(message = "monthlyBudget must be positive")
    private BigDecimal monthlyBudget;
}
