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

/**
 * Stores OAuth credentials and webhook state for one Google Calendar account.
 *
 * _id is the composite storeId = userId + ":" + email, so a single user can
 * connect multiple Google accounts simultaneously.  Use GoogleSyncStore.storeId()
 * to build the key and GoogleSyncStoreRepository.findByUserId() to list all
 * accounts for a user.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "google_sync_stores")
@CompoundIndex(def = "{'userId': 1, 'email': 1}", unique = true)
public class GoogleSyncStore {

    /**
     * Composite primary key: userId + ":" + email.
     * Allows multiple Google Calendar accounts per user.
     */
    @Id
    private String id;

    @Indexed
    private String userId;

    private String email;

    // Encrypted access / refresh tokens
    private String accessToken;
    private String refreshToken;

    private String currentSyncToken;
    private String webhookChannelId;
    private String webhookResourceId;
    private Instant webhookExpiration;

    private Instant lastSyncedAt;

    public static String storeId(String userId, String email) {
        return userId + ":" + email;
    }
}
