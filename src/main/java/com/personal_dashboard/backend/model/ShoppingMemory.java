package com.personal_dashboard.backend.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * What the grocery list has learned about the user — one document per user. Each remembered
 * item keeps the aisle it was last filed in (so moving "Milk" to Drinks once files it there
 * from then on) and how often it was added (behind "Buy again" and the add bar's autocomplete).
 *
 * <p>A list rather than a map on purpose: item names may contain dots, which Mongo map keys
 * cannot. Capped at {@code ShoppingListService.MAX_REMEMBERED}, least recently added first out.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "shopping_memory")
public class ShoppingMemory implements UserOwnedDocument {

    @Id
    private String id;

    private String userId;

    @Builder.Default
    private List<Remembered> items = new ArrayList<>();

    @LastModifiedDate
    private Instant updatedAt;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Remembered {
        /** Display name as last typed. */
        private String name;
        private String category;
        /** How many times it has been put on the list. */
        private int count;
        private Instant lastAdded;
        /** True once the user moved it themselves — their choice then always beats the guess. */
        private boolean userFiled;
    }
}
