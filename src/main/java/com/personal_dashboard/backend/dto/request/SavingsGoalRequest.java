package com.personal_dashboard.backend.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/** Create or fully replace a savings goal's plan. Money moves through the action endpoints. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SavingsGoalRequest {

    @NotBlank(message = "Name is required")
    @Size(max = 40, message = "Name is too long")
    private String name;

    @Pattern(regexp = "PURCHASE|TRIP|SAFETY_NET|OPEN", message = "kind must be PURCHASE, TRIP, SAFETY_NET or OPEN")
    private String kind;

    /** Icon key from the client's goal-icons set; unknown keys fall back to the kind's default. */
    @Size(max = 24, message = "icon is too long")
    private String icon;

    @Size(max = 16, message = "color is too long")
    private String color;

    /** Optional when a list price is given (target = list − exchange − card offer) or for OPEN goals. */
    @Positive(message = "targetAmount must be greater than 0")
    private BigDecimal targetAmount;

    @DecimalMin(value = "0.0", message = "listPrice cannot be negative")
    private BigDecimal listPrice;

    @DecimalMin(value = "0.0", message = "exchangeValue cannot be negative")
    private BigDecimal exchangeValue;

    @DecimalMin(value = "0.0", message = "cardOffer cannot be negative")
    private BigDecimal cardOffer;

    @Pattern(regexp = "\\d{4}-\\d{2}-\\d{2}", message = "targetDate must be in format YYYY-MM-DD")
    private String targetDate;

    @DecimalMin(value = "0.0", message = "plannedMonthly cannot be negative")
    private BigDecimal plannedMonthly;

    @Size(max = 40, message = "keptAt is too long")
    private String keptAt;

    @Pattern(regexp = "\\d{4}-\\d{2}-\\d{2}", message = "startDate must be in format YYYY-MM-DD")
    private String startDate;

    /** Funding order — lower is funded first. "Fund this first" sends one below the current minimum. */
    private Integer priority;

    /** Update only: ACTIVE or PAUSED (ACTIVE also reopens a bought or archived goal). */
    @Pattern(regexp = "ACTIVE|PAUSED", message = "status must be ACTIVE or PAUSED")
    private String status;
}
