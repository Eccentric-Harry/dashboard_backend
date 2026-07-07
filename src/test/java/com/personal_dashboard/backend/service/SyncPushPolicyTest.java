package com.personal_dashboard.backend.service;

import com.personal_dashboard.backend.model.DailyTask;
import com.personal_dashboard.backend.model.EventOrigin;
import com.personal_dashboard.backend.model.GoogleSyncStore;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SyncPushPolicyTest {

    private GoogleSyncStore store(String email, boolean crossAccountPush) {
        return GoogleSyncStore.builder().email(email).crossAccountPush(crossAccountPush).build();
    }

    private DailyTask task(EventOrigin origin) {
        return DailyTask.builder().id("T1").origin(origin).build();
    }

    @Test
    void localOrigin_pushesToAnyAccount() {
        assertTrue(SyncPushPolicy.shouldPush(task(EventOrigin.local()), store("a@x.com", false)));
    }

    @Test
    void nullOrigin_treatedAsLocal_pushes() {
        assertTrue(SyncPushPolicy.shouldPush(task(null), store("a@x.com", false)));
    }

    @Test
    void googleOrigin_neverPushesBackToSourceAccount() {
        DailyTask t = task(EventOrigin.google("a@x.com", "primary"));
        // Even if cross-account is enabled, the SOURCE account is always excluded.
        assertFalse(SyncPushPolicy.shouldPush(t, store("a@x.com", true)));
    }

    @Test
    void googleOrigin_otherAccount_onlyWithOptIn() {
        DailyTask t = task(EventOrigin.google("a@x.com", "primary"));
        assertFalse(SyncPushPolicy.shouldPush(t, store("b@x.com", false))); // no fan-out by default
        assertTrue(SyncPushPolicy.shouldPush(t, store("b@x.com", true)));   // opt-in fan-out
    }
}
