package com.personal_dashboard.backend.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * One line on the user's shopping list (/shopping). The list is a single flat collection;
 * "by category" is a property of the read, not of the storage — the category key is stored
 * on the item and {@code ShoppingCategories} owns the aisle order they are shown in.
 *
 * <p>An item is either <em>to get</em> or <em>in the basket</em> ({@link #checked}). Checking
 * an item off never deletes it, so a mis-tap is a tap to undo and "buy it again" is one tap.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "shopping_items")
public class ShoppingItem implements UserOwnedDocument {

    @Id
    private String id;

    private String userId;

    /** What to buy, as the user typed it ("Tomatoes"). Unique per user, case-insensitively. */
    private String name;

    /** A {@code ShoppingCategories} key; never null once saved. */
    private String category;

    /** Free text — "2 kg", "3", "1 L". Not parsed; the app does no arithmetic on it. */
    private String quantity;

    /** A brand or a reminder: "the Amul one", "ripe, not green". */
    private String note;

    private boolean checked;

    /** When it went into the basket; null while it is still to get. */
    private Instant checkedAt;

    @CreatedDate
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;
}
