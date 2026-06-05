package com.saga.orchestrator.definition;

import java.time.Duration;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

public record RetryPolicy(
        int maxAttempts,
        Duration initialDelay,
        double multiplier,
        boolean jitter
) {
    public RetryPolicy {
        if (maxAttempts < 1) throw new IllegalArgumentException("maxAttempts must be >= 1");
        if (initialDelay.isNegative()) throw new IllegalArgumentException("initialDelay must not be negative");
        if (multiplier < 1.0) throw new IllegalArgumentException("multiplier must be >= 1.0");
    }

    /** No retry: a single attempt, zero wait. */
    public static RetryPolicy noRetry() {
        return new RetryPolicy(1, Duration.ZERO, 1.0, false);
    }

    /** Exponential backoff with jitter: delay doubles each attempt, ±50% randomisation. */
    public static RetryPolicy exponential(int maxAttempts, Duration initialDelay) {
        return new RetryPolicy(maxAttempts, initialDelay, 2.0, true);
    }

    /** Fixed interval: every retry waits the same duration, no jitter. */
    public static RetryPolicy fixed(int maxAttempts, Duration delay) {
        return new RetryPolicy(maxAttempts, delay, 1.0, false);
    }

    /**
     * Compute the wait before attempt {@code attempt} (1-indexed).
     * Attempt 1 is always immediate (no delay).
     * Attempt 2 waits {@code initialDelay * multiplier^0}.
     * Attempt N waits {@code initialDelay * multiplier^(N-2)}.
     */
    public Duration delayForAttempt(int attempt) {
        return delayForAttempt(attempt, ThreadLocalRandom.current());
    }

    /** Testable overload: pass a seeded Random to get deterministic jitter. */
    public Duration delayForAttempt(int attempt, Random random) {
        if (attempt <= 1) return Duration.ZERO;
        double delayMs = initialDelay.toMillis() * Math.pow(multiplier, attempt - 2);
        if (jitter) {
            // Apply 50–100% of the calculated delay to prevent thundering herd
            delayMs = delayMs * (0.5 + random.nextDouble() * 0.5);
        }
        return Duration.ofMillis(Math.max(0L, (long) delayMs));
    }
}
