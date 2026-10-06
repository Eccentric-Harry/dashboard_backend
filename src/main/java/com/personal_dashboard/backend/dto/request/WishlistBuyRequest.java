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

/** "Bought it": what it cost is logged as spending in Finance. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WishlistBuyRequest {

    @NotNull(message = "Price is required")
    @Positive(message = "Price must be greater than 0")
    private BigDecimal price;

    /** Spending category; defaults to Shopping. */
    @Size(max = 60, message = "Category is too long")
    private String category;

    /** Defaults to today. */
    @Pattern(regexp = "\\d{4}-\\d{2}-\\d{2}", message = "date must be in format YYYY-MM-DD")
    private String date;
}
