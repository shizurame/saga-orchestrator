package com.saga.orchestrator.definition;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class RetryPolicyTest {

    // ── Factory methods ───────────────────────────────────────────────────────

    @Test
    void noRetry_maxAttemptsIsOne_noDelay() {
        RetryPolicy policy = RetryPolicy.noRetry();
        assertThat(policy.maxAttempts()).isEqualTo(1);
        assertThat(policy.delayForAttempt(1)).isEqualTo(Duration.ZERO);
    }

    @Test
    void fixed_allAttemptsWaitSameDelay() {
        RetryPolicy policy = RetryPolicy.fixed(5, Duration.ofMillis(500));
        assertThat(policy.maxAttempts()).isEqualTo(5);
        // attempt 1 = immediate
        assertThat(policy.delayForAttempt(1)).isEqualTo(Duration.ZERO);
        // attempt 2 onwards = fixed delay
        assertThat(policy.delayForAttempt(2)).isEqualTo(Duration.ofMillis(500));
        assertThat(policy.delayForAttempt(3)).isEqualTo(Duration.ofMillis(500));
        assertThat(policy.delayForAttempt(4)).isEqualTo(Duration.ofMillis(500));
    }

    @Test
    void exponential_delayDoublesEachAttempt() {
        // Use jitter=false variant to get deterministic values
        RetryPolicy policy = new RetryPolicy(5, Duration.ofMillis(100), 2.0, false);

        assertThat(policy.delayForAttempt(1)).isEqualTo(Duration.ZERO);          // no delay on first attempt
        assertThat(policy.delayForAttempt(2)).isEqualTo(Duration.ofMillis(100)); // 100 * 2^0
        assertThat(policy.delayForAttempt(3)).isEqualTo(Duration.ofMillis(200)); // 100 * 2^1
        assertThat(policy.delayForAttempt(4)).isEqualTo(Duration.ofMillis(400)); // 100 * 2^2
        assertThat(policy.delayForAttempt(5)).isEqualTo(Duration.ofMillis(800)); // 100 * 2^3
    }

    @Test
    void exponential_factoryUsesTwoAsMultiplier() {
        RetryPolicy policy = RetryPolicy.exponential(3, Duration.ofMillis(100));
        assertThat(policy.multiplier()).isEqualTo(2.0);
        assertThat(policy.jitter()).isTrue();
        assertThat(policy.maxAttempts()).isEqualTo(3);
    }

    // ── Jitter ────────────────────────────────────────────────────────────────

    @Test
    void jitter_delayIsBetween50And100PercentOfCalculatedDelay() {
        RetryPolicy policy = new RetryPolicy(3, Duration.ofMillis(1000), 1.0, true);
        // Attempt 2: calculated delay = 1000ms; with jitter: [500ms, 1000ms]
        Random seeded = new Random(42);
        Duration delay = policy.delayForAttempt(2, seeded);
        assertThat(delay.toMillis()).isBetween(500L, 1000L);
    }

    @Test
    void jitter_differentSeedsProduceDifferentDelays() {
        RetryPolicy policy = new RetryPolicy(5, Duration.ofMillis(1000), 1.0, true);
        Duration d1 = policy.delayForAttempt(2, new Random(1));
        Duration d2 = policy.delayForAttempt(2, new Random(99));
        // With different seeds, delays should differ (statistically overwhelmingly likely)
        assertThat(d1).isNotEqualTo(d2);
    }

    @Test
    void noJitter_sameSeedProducesSameDelay() {
        RetryPolicy policy = new RetryPolicy(3, Duration.ofMillis(500), 2.0, false);
        assertThat(policy.delayForAttempt(3, new Random(1)))
            .isEqualTo(policy.delayForAttempt(3, new Random(99))); // no jitter = deterministic
    }

    // ── Validation ────────────────────────────────────────────────────────────

    @Test
    void invalid_maxAttemptsZero_throwsIllegalArgument() {
        assertThatThrownBy(() -> new RetryPolicy(0, Duration.ofMillis(100), 1.0, false))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("maxAttempts");
    }

    @Test
    void invalid_negativeDelay_throwsIllegalArgument() {
        assertThatThrownBy(() -> new RetryPolicy(3, Duration.ofMillis(-1), 1.0, false))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("initialDelay");
    }

    @Test
    void invalid_multiplierBelowOne_throwsIllegalArgument() {
        assertThatThrownBy(() -> new RetryPolicy(3, Duration.ofMillis(100), 0.5, false))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("multiplier");
    }
}
