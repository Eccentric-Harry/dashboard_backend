package com.personal_dashboard.backend.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Move an item into the basket ({@code true}) or back onto the list ({@code false}). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ShoppingCheckedRequest {

    @NotNull(message = "checked is required")
    private Boolean checked;
}
