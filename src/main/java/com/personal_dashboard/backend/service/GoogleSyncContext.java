package com.personal_dashboard.backend.service;

public class GoogleSyncContext {
    private static final ThreadLocal<Boolean> BYPASS = ThreadLocal.withInitial(() -> false);

    public static boolean isBypass() {
        return BYPASS.get();
    }

    public static void setBypass(boolean bypass) {
        BYPASS.set(bypass);
    }

    public static void clear() {
        BYPASS.remove();
    }
}
