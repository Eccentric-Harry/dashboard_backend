package com.personal_dashboard.backend.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/** Create or fully replace a wish. Buying, saving for and letting go have their own endpoints. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WishlistItemRequest {

    @NotBlank(message = "Name is required")
    @Size(max = 120, message = "Name is too long")
    private String name;

    @Size(max = 2000, message = "Link is too long")
    private String url;

    @Size(max = 60, message = "Store is too long")
    private String store;

    @Size(max = 2000, message = "Image link is too long")
    private String imageUrl;

    @DecimalMin(value = "0.0", message = "Price cannot be negative")
    private BigDecimal price;

    @Pattern(regexp = "NEED|WANT", message = "priority must be NEED or WANT")
    private String priority;

    @Size(max = 280, message = "Note is too long")
    private String note;
}
