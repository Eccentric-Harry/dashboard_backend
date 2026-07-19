package com.personal_dashboard.backend.security;

import lombok.extern.slf4j.Slf4j;

import java.util.Optional;

@Slf4j
public final class UserContext {

    private static final ThreadLocal<String> CURRENT_USER_ID = new ThreadLocal<>();

    private UserContext() {
    }

    public static void setUserId(String userId) {
        log.debug("Binding userId={} to current request thread", userId);
        CURRENT_USER_ID.set(userId);
    }

    public static Optional<String> getUserId() {
        return Optional.ofNullable(CURRENT_USER_ID.get())
                .filter(userId -> !userId.isBlank());
    }

    public static String getRequiredUserId() {
        return getUserId()
                .orElseThrow(() -> {
                    log.warn("No authenticated user bound to current request thread");
                    return new IllegalStateException("No authenticated user is bound to the current request");
                });
    }

    public static void clear() {
        log.debug("Clearing userId binding from current request thread");
        CURRENT_USER_ID.remove();
    }
}
