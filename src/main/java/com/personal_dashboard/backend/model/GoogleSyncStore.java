package com.personal_dashboard.backend.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "google_sync_stores")
public class GoogleSyncStore {

    @Id
    private String userId;

    private String email;

    // Encrypted fields
    private String accessToken;
    private String refreshToken;

    private String currentSyncToken;
    private String webhookChannelId;
    private String webhookResourceId;
    private Instant webhookExpiration;

    private Instant lastSyncedAt;
}
