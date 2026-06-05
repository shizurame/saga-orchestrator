package com.saga.orchestrator.integration;

import com.saga.orchestrator.definition.ExecutionResult;
import com.saga.orchestrator.showcase.CreditCheckService;
import com.saga.orchestrator.showcase.TransferContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Idempotency under 1000 concurrent callers with the same idempotency key.
 *
 * Simulates a retry storm: 1000 threads simultaneously submit the same step with
 * the same idempotency key (as happens when 1000 orchestrator nodes all retry a
 * timed-out step at the same time). The participant must execute exactly once
 * and return the same result to all 1000 callers.
 *
 * The participant's idempotency mechanism: ConcurrentHashMap.computeIfAbsent.
 * Per Java spec, computeIfAbsent is atomic — the mapping function is applied
 * exactly once per key even under concurrent access from multiple threads.
 *
 * This test verifies:
 *   1. The behavior supplier is called exactly once (no duplicate side effects)
 *   2. All 1000 calls return an identical Success result (same credit limit value)
 *   3. No exceptions thrown under concurrent load
 */
class IdempotencyUnder1000RetriesTest extends AbstractIntegrationTest {

    @Autowired CreditCheckService creditCheckService;

    @BeforeEach
    void resetParticipants() {
        creditCheckService.reset();
    }

    @Test
    void sameIdempotencyKey_1000ConcurrentCallers_participantExecutedOnce() throws InterruptedException {
        String idempotencyKey = "idem-test-key-storm-" + System.nanoTime();
        AtomicInteger actualExecutionCount = new AtomicInteger(0);

        // Override behavior to count actual executions (cache miss = real execution)
        creditCheckService.setBehavior(() -> {
            actualExecutionCount.incrementAndGet();
            return new ExecutionResult.Success(Map.of("creditApproved", true, "creditLimit", 50000));
        });

        TransferContext ctx = new TransferContext("cust-idem", "acc-idem", "acc-dst-idem",
                new BigDecimal("100"));

        int threadCount = 1000;
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startGate = new CountDownLatch(1);
        List<Future<ExecutionResult>> futures = new ArrayList<>(threadCount);

        for (int i = 0; i < threadCount; i++) {
            futures.add(pool.submit(() -> {
                startGate.await(); // all threads race simultaneously
                return creditCheckService.forwardAction().execute(ctx, idempotencyKey);
            }));
        }

        startGate.countDown(); // release all 1000 threads at once

        List<ExecutionResult> results = new ArrayList<>(threadCount);
        for (Future<ExecutionResult> f : futures) {
            try {
                results.add(f.get(5, TimeUnit.SECONDS));
            } catch (ExecutionException | TimeoutException e) {
                throw new AssertionError("Thread failed: " + e.getMessage(), e);
            }
        }

        pool.shutdown();

        // Exactly one real execution despite 1000 concurrent callers
        assertThat(actualExecutionCount.get())
                .as("participant must execute exactly once regardless of concurrent retry count")
                .isEqualTo(1);

        // All 1000 callers received identical Success results
        assertThat(results).hasSize(threadCount)
                .allSatisfy(r -> assertThat(r).isInstanceOf(ExecutionResult.Success.class));

        // All results have the same credit limit (same cached result returned to all callers)
        long distinctCreditLimits = results.stream()
                .filter(r -> r instanceof ExecutionResult.Success)
                .map(r -> ((Map<?, ?>) ((ExecutionResult.Success) r).result()).get("creditLimit"))
                .distinct()
                .count();
        assertThat(distinctCreditLimits)
                .as("all callers must receive the same cached result")
                .isEqualTo(1);
    }
}
