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

/** Sets money aside for a goal, or takes some back out. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GoalMoneyRequest {

    @NotNull(message = "Amount is required")
    @Positive(message = "Amount must be greater than 0")
    private BigDecimal amount;

    /** Defaults to today. */
    @Pattern(regexp = "\\d{4}-\\d{2}-\\d{2}", message = "date must be in format YYYY-MM-DD")
    private String date;

    /** Free text; for a take-out, the reason ("Emergency", "Changed plans"). */
    @Size(max = 80, message = "note is too long")
    private String note;
}
