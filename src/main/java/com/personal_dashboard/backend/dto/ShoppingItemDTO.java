package com.personal_dashboard.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/** A shopping-list line as the client sees it. Lists come back already in aisle order. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ShoppingItemDTO {
    private String id;
    private String name;
    /** A {@code ShoppingCategories} key. */
    private String category;
    private String quantity;
    private String note;
    private boolean checked;
    private Instant checkedAt;
    private Instant createdAt;
}
