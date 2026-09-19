package com.personal_dashboard.backend.util;

import com.personal_dashboard.backend.security.UserContext;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * Runs independent database reads concurrently. Each Atlas round trip costs ~55 ms from
 * the Render region, so a summary built from N sequential queries costs N round trips;
 * forked, it costs roughly the slowest one.
 *
 * <p>Reads only. Forked tasks run with the caller's user bound via
 * {@link UserContext#propagate}, so user-scoped services keep working; writes should stay
 * on the request thread where their ordering is obvious.
 */
public final class ParallelReads {

    // Virtual threads: every forked task just blocks on a network round trip.
    private static final ExecutorService EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

    private ParallelReads() {
    }

    /** Starts {@code read} on another thread. Must be called on the request thread. */
    public static <T> CompletableFuture<T> fork(Supplier<T> read) {
        return CompletableFuture.supplyAsync(UserContext.propagate(read), EXECUTOR);
    }

    /**
     * Waits for a forked read, rethrowing its original exception rather than the
     * {@link CompletionException} wrapper so the global exception handler still maps it.
     */
    public static <T> T join(CompletableFuture<T> future) {
        try {
            return future.join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof RuntimeException cause) {
                throw cause;
            }
            if (e.getCause() instanceof Error error) {
                throw error;
            }
            throw e;
        }
    }
}
