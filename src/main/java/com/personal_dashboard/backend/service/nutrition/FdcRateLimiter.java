package com.personal_dashboard.backend.service.nutrition;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Guards the shared FoodData Central quota.
 *
 * <p>api.data.gov allows 1,000 requests per hour per key and answers a breach with HTTP 429
 * plus a one-hour block. A block would be considerably worse than a few unmatched
 * ingredients: it would take out nutrient lookups for every user for an hour. So this
 * limiter spends a deliberately conservative budget and, when it runs out, declines the
 * lookup rather than risking the block.</p>
 *
 * <p>The local count is a floor, not the truth — the server's own
 * {@code X-RateLimit-Remaining} header is authoritative and is folded in via
 * {@link #syncRemaining(int)}, which matters because the quota is per key and other
 * instances of this application may be spending it too.</p>
 */
@Component
@Slf4j
public class FdcRateLimiter {

    private static final Duration WINDOW = Duration.ofHours(1);

    /**
     * Requests to spend per hour. Below the documented 1,000 on purpose: the headroom
     * absorbs other deployments sharing the key and any miscount around a window edge.
     */
    @Value("${nutrition.usda.hourly-budget:800}")
    private int hourlyBudget;

    private final AtomicInteger used = new AtomicInteger(0);
    private volatile Instant windowStart = Instant.now();
    /** Set when the server returns 429; no request is attempted until it passes. */
    private volatile Instant blockedUntil = Instant.EPOCH;
    /** Server-reported remaining quota, or -1 when never reported. */
    private volatile int serverRemaining = -1;

    /**
     * Attempts to reserve one request.
     *
     * @return true if the caller may proceed
     */
    public synchronized boolean tryAcquire() {
        Instant now = Instant.now();

        if (now.isBefore(blockedUntil)) {
            return false;
        }
        if (Duration.between(windowStart, now).compareTo(WINDOW) >= 0) {
            windowStart = now;
            used.set(0);
            serverRemaining = -1;
        }
        if (serverRemaining == 0) {
            return false;
        }
        if (used.get() >= hourlyBudget) {
            log.warn("[FdcRateLimiter] Local hourly budget of {} exhausted; "
                    + "declining live lookups until the window resets.", hourlyBudget);
            return false;
        }
        used.incrementAndGet();
        return true;
    }

    /** Folds in the server's authoritative remaining count from {@code X-RateLimit-Remaining}. */
    public void syncRemaining(int remaining) {
        if (remaining < 0) return;
        serverRemaining = remaining;
        if (remaining < 50) {
            log.warn("[FdcRateLimiter] FoodData Central quota nearly exhausted: {} left this hour.",
                    remaining);
        }
    }

    /** Records a 429 and stops all live lookups for the block period. */
    public void recordRateLimited() {
        blockedUntil = Instant.now().plus(WINDOW);
        serverRemaining = 0;
        log.warn("[FdcRateLimiter] FoodData Central returned 429. "
                + "Pausing live lookups until {}.", blockedUntil);
    }

    /** True when a live lookup would currently be refused. */
    public boolean isExhausted() {
        return Instant.now().isBefore(blockedUntil)
                || serverRemaining == 0
                || used.get() >= hourlyBudget;
    }

    public int getUsedThisWindow() { return used.get(); }
    public int getHourlyBudget() { return hourlyBudget; }
    public int getServerRemaining() { return serverRemaining; }
    public Instant getBlockedUntil() { return blockedUntil; }
}
