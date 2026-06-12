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
public class LendingRecordRequest {

    @NotBlank(message = "Borrower is required")
    private String borrower;

    @NotNull(message = "Amount is required")
    @DecimalMin(value = "0.0", inclusive = false, message = "Amount must be greater than 0")
    private BigDecimal amount;

    @NotBlank(message = "Date is required")
    @Pattern(regexp = "\\d{4}-\\d{2}-\\d{2}", message = "Date must be in format YYYY-MM-DD")
    private String date;

    @Pattern(regexp = "(\\d{4}-\\d{2}-\\d{2})?", message = "Due Date must be in format YYYY-MM-DD or empty")
    private String dueDate;

    @NotBlank(message = "Status is required")
    @Pattern(regexp = "Pending|Repaid", message = "Status must be 'Pending' or 'Repaid'")
    private String status;

    private String notes;
}
