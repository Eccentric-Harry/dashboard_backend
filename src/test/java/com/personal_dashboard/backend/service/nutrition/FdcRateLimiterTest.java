package com.personal_dashboard.backend.service.nutrition;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the quota guard.
 *
 * <p>Exceeding the api.data.gov limit costs a one-hour block on the key, which would take
 * nutrient lookups out for every user. These tests pin the behaviour that prevents that.</p>
 */
class FdcRateLimiterTest {

    private FdcRateLimiter limiter;

    @BeforeEach
    void setUp() {
        limiter = new FdcRateLimiter();
        ReflectionTestUtils.setField(limiter, "hourlyBudget", 3);
    }

    @Test
    void allowsUpToTheBudgetThenDeclines() {
        assertTrue(limiter.tryAcquire());
        assertTrue(limiter.tryAcquire());
        assertTrue(limiter.tryAcquire());

        assertFalse(limiter.tryAcquire(), "the fourth request exceeds a budget of three");
        assertTrue(limiter.isExhausted());
        assertEquals(3, limiter.getUsedThisWindow());
    }

    @Test
    void aServerReported429BlocksFurtherRequests() {
        assertTrue(limiter.tryAcquire());

        limiter.recordRateLimited();

        assertFalse(limiter.tryAcquire());
        assertTrue(limiter.isExhausted());
        assertTrue(limiter.getBlockedUntil().isAfter(java.time.Instant.now()));
    }

    @Test
    void trustsTheServersRemainingCountOverTheLocalOne() {
        // The quota is per key, so other deployments may have spent it even though
        // this process has barely used its local budget.
        limiter.syncRemaining(0);

        assertFalse(limiter.tryAcquire());
        assertTrue(limiter.isExhausted());
    }

    @Test
    void keepsGoingWhileTheServerReportsHeadroom() {
        limiter.syncRemaining(500);

        assertTrue(limiter.tryAcquire());
        assertEquals(500, limiter.getServerRemaining());
    }

    @Test
    void ignoresAnUnparseableRemainingValue() {
        limiter.syncRemaining(-1);

        assertEquals(-1, limiter.getServerRemaining());
        assertTrue(limiter.tryAcquire(), "an absent header must not be read as zero quota");
    }
}
