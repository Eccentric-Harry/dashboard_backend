package com.personal_dashboard.backend.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.Locale;

/**
 * Binds one dashboard category ("Movies", "Learning", "Personal", …) to one Google
 * Tasks list, per connected account. One list per category is what makes the phone
 * widget useful: a widget pins a single list, so mirroring categories as lists lets
 * the user put just "Personal" on their home screen.
 *
 * <p>The binding is stored rather than resolved by name on every sync for two reasons:
 * a list renamed on the phone must keep its link, and matching by title would re-create
 * a list the moment Google returned it with different casing.
 *
 * <p>_id is a composite: userId + ":" + accountEmail + ":" + normalised category.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "google_task_lists")
@CompoundIndex(def = "{'userId': 1, 'accountEmail': 1}")
public class GoogleTaskListMapping implements UserOwnedDocument {

    /**
     * Pseudo-category used by the SINGLE list strategy, where every task shares one list
     * regardless of category. A real category can never collide with it — categories are
     * user-entered text and this is not a legal one.
     */
    public static final String ALL_CATEGORIES = "__all__";

    @Id
    private String id;

    @Indexed
    private String userId;

    private String accountEmail;

    /** The dashboard category exactly as stored on the task (display casing). */
    private String category;

    /** Google's task-list id. */
    private String googleTaskListId;

    /** The list title we last wrote to Google; used to detect a rename on our side. */
    private String googleTaskListTitle;

    /**
     * Set when the list has been deleted in Google. The binding is kept rather than
     * removed so a later poll can recreate the list deliberately instead of silently
     * scattering the category's tasks into the default list.
     */
    @Builder.Default
    private Boolean missing = false;

    /**
     * True when this dashboard created the list, false when it adopted one the user
     * already had. Only a list we created is ever offered for cleanup — deleting a list
     * in someone's Google account is irreversible, and their own lists are not ours to
     * tidy. Null on rows written before this was tracked, treated as "not ours".
     */
    @Builder.Default
    private Boolean createdByUs = false;

    private Instant createdAt;
    private Instant lastSyncedAt;

    /** Categories are matched case- and whitespace-insensitively; display casing is kept separately. */
    public static String normalizeCategory(String category) {
        String value = (category == null || category.isBlank()) ? "General" : category.trim();
        return value.toLowerCase(Locale.ROOT);
    }

    public static String compositeId(String userId, String accountEmail, String category) {
        return userId + ":" + accountEmail + ":" + normalizeCategory(category);
    }
}
