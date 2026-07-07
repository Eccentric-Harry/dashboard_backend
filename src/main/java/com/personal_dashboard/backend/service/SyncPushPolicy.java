package com.personal_dashboard.backend.service;

import com.personal_dashboard.backend.model.DailyTask;
import com.personal_dashboard.backend.model.EventOrigin;
import com.personal_dashboard.backend.model.GoogleSyncStore;

/**
 * Decides whether a local change to {@code task} should be pushed to a given
 * connected Google account. Two rules:
 *
 *  1. Loop prevention (guardrail #4): a GOOGLE-origin event is NEVER pushed back
 *     to the account it was pulled from — that would echo our own inbound write.
 *  2. Cross-account fan-out (#10): a GOOGLE-origin event is pushed to a DIFFERENT
 *     account only when that account has opted in (crossAccountPush=true). Without
 *     opt-in, pulled invites do not silently fan out across every connected calendar.
 *
 * LOCAL-origin events (and legacy events with a null origin) push to all accounts.
 */
public final class SyncPushPolicy {

    private SyncPushPolicy() {}

    public static boolean shouldPush(DailyTask task, GoogleSyncStore store) {
        EventOrigin origin = task.getOrigin();

        // Legacy/unknown origin behaves like LOCAL (preserves prior behavior).
        if (origin == null || origin.isLocal()) {
            return true;
        }

        // GOOGLE-origin:
        String originAccount = origin.getAccountId();
        boolean sameAccount = originAccount != null && originAccount.equalsIgnoreCase(store.getEmail());
        if (sameAccount) {
            return false; // never push back to the source account
        }
        return Boolean.TRUE.equals(store.getCrossAccountPush()); // opt-in fan-out only
    }
}
