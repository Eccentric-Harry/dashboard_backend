package com.personal_dashboard.backend.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "push_subscriptions")
public class PushSubscription {

    @Id
    private String id;

    private String endpoint;
    private String p256dh;
    private String auth;
    private String timezone;

    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();
}
