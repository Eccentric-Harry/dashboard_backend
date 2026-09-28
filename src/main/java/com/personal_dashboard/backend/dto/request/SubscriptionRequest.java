package com.personal_dashboard.backend.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/** Create or fully replace a recurring bill / subscription. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SubscriptionRequest {

    @NotBlank(message = "Name is required")
    @Size(max = 80, message = "Name is too long")
    private String name;

    @NotNull(message = "Cost is required")
    @Positive(message = "Cost must be greater than 0")
    private BigDecimal cost;

    /** Any date the bill falls due on (yyyy-MM-dd). Optional. */
    @Pattern(regexp = "\\d{4}-\\d{2}-\\d{2}", message = "billingDate must be in format YYYY-MM-DD")
    private String billingDate;

    @Size(max = 60, message = "Category is too long")
    private String category;

    @Pattern(regexp = "DAY|WEEK|MONTH|YEAR", message = "intervalUnit must be DAY, WEEK, MONTH or YEAR")
    private String intervalUnit;

    @Min(value = 1, message = "intervalCount must be at least 1")
    @Max(value = 366, message = "intervalCount is too large")
    private Integer intervalCount;
}
