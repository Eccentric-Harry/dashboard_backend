package com.personal_dashboard.backend.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** Several lines at once — "milk, eggs, 2 kg tomatoes" typed in one go, or an undo of a clear. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ShoppingItemsBatchRequest {

    @NotEmpty(message = "Add at least one item")
    @Size(max = 50, message = "Add up to 50 items at a time")
    @Valid
    private List<ShoppingItemRequest> items;
}
