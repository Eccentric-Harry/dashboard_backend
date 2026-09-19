package com.personal_dashboard.backend.util;

import com.personal_dashboard.backend.security.UserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Forked reads must stay user-scoped (UserContext is a ThreadLocal, so without
 * propagation a forked read would fail — or worse, see another request's user) and
 * must surface the original exception so the global handler still maps it.
 */
class ParallelReadsTest {

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void forkedReadRunsOnAnotherThreadAsTheCallingUser() {
        UserContext.setUserId("harry");
        Thread caller = Thread.currentThread();

        CompletableFuture<String> read = ParallelReads.fork(() -> {
            assertNotSame(caller, Thread.currentThread());
            return UserContext.getRequiredUserId();
        });

        assertEquals("harry", ParallelReads.join(read));
    }

    @Test
    void forkRequiresABoundUser() {
        assertThrows(IllegalStateException.class, () -> ParallelReads.fork(() -> "x"));
    }

    @Test
    void joinRethrowsTheOriginalException() {
        UserContext.setUserId("harry");
        CompletableFuture<Object> read = ParallelReads.fork(() -> {
            throw new IllegalArgumentException("bad input");
        });

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, () -> ParallelReads.join(read));
        assertEquals("bad input", thrown.getMessage());
    }
}
