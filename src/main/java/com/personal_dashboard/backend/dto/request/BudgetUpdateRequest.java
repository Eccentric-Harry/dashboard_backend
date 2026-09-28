package com.personal_dashboard.backend.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * Sets the user's monthly spending budget to an absolute value, and optionally what it
 * covers. Omitted optional fields leave the stored setting unchanged.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BudgetUpdateRequest {

    @NotNull(message = "monthlyBudget is required")
    @Positive(message = "monthlyBudget must be positive")
    private BigDecimal monthlyBudget;

    @Pattern(regexp = "ALL|FLEX", message = "budgetScope must be 'ALL' or 'FLEX'")
    private String budgetScope;

    @Size(max = 40, message = "Too many fixed categories")
    private List<@Size(min = 1, max = 60) String> fixedCategories;
}
