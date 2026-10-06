package com.personal_dashboard.backend.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/** "Done shopping": empties the basket and, when an amount is given, logs the shop as one expense. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ShoppingCheckoutRequest {

    /** What the shop cost. Null or zero = just clear the basket, log nothing. */
    @DecimalMin(value = "0.0", message = "Amount cannot be negative")
    private BigDecimal amount;

    /** Where — "DMart", "Ratnadeep". Goes into the ledger description. */
    @Size(max = 60, message = "Store is too long")
    private String store;

    /** Defaults to Groceries. */
    @Size(max = 60, message = "Category is too long")
    private String category;

    /** Defaults to today. */
    @Pattern(regexp = "\\d{4}-\\d{2}-\\d{2}", message = "date must be in format YYYY-MM-DD")
    private String date;
}
