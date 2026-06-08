package com.personal_dashboard.backend.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PushSubscriptionRequest {

    @NotBlank(message = "Endpoint is required")
    private String endpoint;

    @NotBlank(message = "p256dh key is required")
    private String p256dh;

    @NotBlank(message = "Auth key is required")
    private String auth;

    @NotBlank(message = "Timezone string is required")
    private String timezone;
}
