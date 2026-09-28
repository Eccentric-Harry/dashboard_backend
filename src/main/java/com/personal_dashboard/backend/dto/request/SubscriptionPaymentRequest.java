package com.personal_dashboard.backend.dto.request;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/** Records one payment against a recurring bill. Both fields are optional. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SubscriptionPaymentRequest {

    /** Payment date (yyyy-MM-dd); defaults to today. */
    @Pattern(regexp = "\\d{4}-\\d{2}-\\d{2}", message = "date must be in format YYYY-MM-DD")
    private String date;

    /** Amount actually paid; defaults to the bill's cost (prices drift — Jio went 349 → 399). */
    @Positive(message = "amount must be greater than 0")
    private BigDecimal amount;
}
