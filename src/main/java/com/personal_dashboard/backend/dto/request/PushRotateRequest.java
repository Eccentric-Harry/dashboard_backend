package com.personal_dashboard.backend.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Sent by the service worker when the browser rotates a push subscription
 * ({@code pushsubscriptionchange}). The service worker has no user session, so the old
 * endpoint — itself an unguessable capability URL minted by the push service — is what
 * authorises the swap.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PushRotateRequest {

    @NotBlank(message = "Old endpoint is required")
    private String oldEndpoint;

    @NotBlank(message = "New endpoint is required")
    private String endpoint;

    @NotBlank(message = "p256dh key is required")
    private String p256dh;

    @NotBlank(message = "Auth key is required")
    private String auth;

    private String timezone;
}
