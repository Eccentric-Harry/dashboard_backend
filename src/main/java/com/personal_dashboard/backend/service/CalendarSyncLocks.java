package com.personal_dashboard.backend.service;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Per-(account,calendar) locks keyed by storeId (= userId:email).
 *
 * A single lock per store serialises all sync work — inbound pull and outbound
 * push — on the same calendar, so two concurrent syncs cannot interleave writes.
 * Locks are reentrant (a 410 full-resync re-enters syncCalendar on the same
 * thread) and shared across GoogleSyncService and the outbound event listener.
 */
@Component
public class CalendarSyncLocks {

    private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    public ReentrantLock forStore(String storeId) {
        return locks.computeIfAbsent(storeId, k -> new ReentrantLock());
    }
}
