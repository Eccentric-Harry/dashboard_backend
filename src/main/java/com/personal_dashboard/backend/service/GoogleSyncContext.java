package com.personal_dashboard.backend.service;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class GoogleSyncContext {
    private static final ThreadLocal<Boolean> BYPASS = ThreadLocal.withInitial(() -> false);

    public static boolean isBypass() {
        return BYPASS.get();
    }

    public static void setBypass(boolean bypass) {
        log.debug("Setting GoogleSyncContext bypass={} on current thread (suppresses outbound echo)", bypass);
        BYPASS.set(bypass);
    }

    public static void clear() {
        BYPASS.remove();
    }
}
