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

/** Records buying the thing a goal saved for. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GoalPurchaseRequest {

    /** What it actually cost — often less than the target after a sale. */
    @NotNull(message = "Price is required")
    @Positive(message = "Price must be greater than 0")
    private BigDecimal price;

    /** Spending category for the purchase; defaults to Shopping. */
    @Size(max = 60, message = "Category is too long")
    private String category;

    /** Defaults to the goal's name. */
    @Size(max = 80, message = "Description is too long")
    private String description;

    /** Defaults to today. */
    @Pattern(regexp = "\\d{4}-\\d{2}-\\d{2}", message = "date must be in format YYYY-MM-DD")
    private String date;
}
