package com.personal_dashboard.backend.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Bulk-moves every transaction in one category: rename/merge it into {@code targetCategory},
 * and/or retype it (e.g. a legacy "To Home" expense bucket → Transfer OUT "Family").
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CategoryReclassifyRequest {

    @NotBlank(message = "category is required")
    @Size(max = 60)
    private String category;

    /** Destination category; omitted keeps the current one (a pure retype). */
    @Size(max = 60)
    private String targetCategory;

    /** New type for every moved row; omitted keeps each row's own type (a pure merge). */
    @Pattern(regexp = "Expense|Income|Transfer", message = "type must be 'Expense', 'Income' or 'Transfer'")
    private String type;

    @Pattern(regexp = "OUT|IN", message = "direction must be 'OUT' or 'IN'")
    private String direction;
}
