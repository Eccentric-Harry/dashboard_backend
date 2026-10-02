package com.personal_dashboard.backend.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CampBuyRequest {

    /** An id from util/CampRules.ITEMS. */
    @NotBlank(message = "itemId is required")
    @Pattern(regexp = "[a-z][a-z-]{1,39}", message = "itemId is not a shop item")
    private String itemId;
}
