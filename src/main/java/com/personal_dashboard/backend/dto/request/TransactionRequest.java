package com.personal_dashboard.backend.dto.request;

import jakarta.validation.constraints.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TransactionRequest {

    @NotBlank(message = "Description is required")
    private String description;

    @NotNull(message = "Amount is required")
    @DecimalMin(value = "0.0", inclusive = false, message = "Amount must be greater than 0")
    private BigDecimal amount;

    @NotBlank(message = "Category is required")
    private String category;

    @NotBlank(message = "Type is required")
    @Pattern(regexp = "Expense|Income|Transfer", message = "Type must be 'Expense', 'Income' or 'Transfer'")
    private String type;

    /** Transfers only; defaults to OUT. Ignored for Expense/Income. */
    @Pattern(regexp = "OUT|IN", message = "Direction must be 'OUT' or 'IN'")
    private String direction;

    /**
     * Links the row to a recurring bill. On update, omitting it keeps the existing link —
     * the edit form never changes it, so a plain edit must not orphan a bill payment.
     */
    @Size(max = 64, message = "subscriptionId is too long")
    private String subscriptionId;

    @NotBlank(message = "Date is required")
    @Pattern(regexp = "\\d{4}-\\d{2}-\\d{2}", message = "Date must be in format YYYY-MM-DD")
    private String date;
}
