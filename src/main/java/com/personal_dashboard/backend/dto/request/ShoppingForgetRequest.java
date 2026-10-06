package com.personal_dashboard.backend.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Drop a remembered item from "Buy again" and autocomplete. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ShoppingForgetRequest {

    @NotBlank(message = "Name is required")
    @Size(max = 80, message = "Name is too long")
    private String name;
}
