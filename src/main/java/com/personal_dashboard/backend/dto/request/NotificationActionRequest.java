package com.personal_dashboard.backend.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A notification action taken from the OS notification itself. The service worker cannot
 * read the user's token, so the one-shot {@code actionToken} delivered inside the (end-to-end
 * encrypted) push payload stands in for it.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NotificationActionRequest {

    @NotBlank(message = "Action token is required")
    private String actionToken;

    /** Snooze length in minutes. Clamped server-side. */
    private Integer minutes;
}
