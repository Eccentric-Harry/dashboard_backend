package com.personal_dashboard.backend.security;

import java.util.Optional;

public final class UserContext {

    private static final ThreadLocal<String> CURRENT_USER_ID = new ThreadLocal<>();

    private UserContext() {
    }

    public static void setUserId(String userId) {
        CURRENT_USER_ID.set(userId);
    }

    public static Optional<String> getUserId() {
        return Optional.ofNullable(CURRENT_USER_ID.get())
                .filter(userId -> !userId.isBlank());
    }

    public static String getRequiredUserId() {
        return getUserId()
                .orElseThrow(() -> new IllegalStateException("No authenticated user is bound to the current request"));
    }

    public static void clear() {
        CURRENT_USER_ID.remove();
    }
}
