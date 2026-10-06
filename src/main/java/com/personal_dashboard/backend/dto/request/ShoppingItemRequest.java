package com.personal_dashboard.backend.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Add a line to the list, or fully replace one. {@code category} is optional on both: left out,
 * a new item is filed by the server's guess from its name and an edited one keeps its category.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ShoppingItemRequest {

    @NotBlank(message = "Name is required")
    @Size(max = 80, message = "Name is too long")
    private String name;

    /** A ShoppingCategories key (PRODUCE, DAIRY, …); validated by the service, which owns the list. */
    @Size(max = 24, message = "category is too long")
    private String category;

    @Size(max = 24, message = "Quantity is too long")
    private String quantity;

    @Size(max = 120, message = "Note is too long")
    private String note;

    /** Add only: put the item straight into the basket (used to undo a delete or "clear basket"). */
    private Boolean checked;
}
