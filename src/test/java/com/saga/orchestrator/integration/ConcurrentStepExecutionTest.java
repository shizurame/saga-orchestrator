package com.saga.orchestrator.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.saga.orchestrator.definition.*;
import com.saga.orchestrator.engine.ForwardExecutionEngine;
import com.saga.orchestrator.persistence.entity.SagaInstance;
import com.saga.orchestrator.persistence.entity.SagaStepExecution;
import com.saga.orchestrator.persistence.repository.SagaInstanceRepository;
import com.saga.orchestrator.persistence.repository.SagaStepExecutionRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the orchestrator-side half of the exactly-once-attempt claim that
 * IdempotencyUnder1000RetriesTest only proves at the participant layer: several threads all
 * racing to execute the SAME (sagaId, stepIndex, attempt) through the real
 * ForwardExecutionEngine — not bypassing it — must result in exactly one persisted intent row,
 * and the participant must see at most one real underlying execution.
 *
 * The step's action is a proper idempotent participant — a ConcurrentHashMap keyed by the
 * idempotency key, exactly like every real showcase participant (CreditCheckService etc.) —
 * not a bare counter. That distinction matters: this architecture's actual contract is
 * "the orchestrator always resends the same key," not "the action is only ever called once."
 * A node that mistook an actively in-flight row for crash-abandoned (a real, narrow race this
 * test also exercises — see ForwardExecutionEngine's OCC catch around persistStepSuccess and
 * SagaStepExecution's @Version) can still invoke the action a second time with the identical
 * key before its write is rejected; correctness there is the participant's cache, same as
 * everywhere else in this project. actualExecutionCount tracks genuine cache misses — the
 * number that must be 1 — while callCount (unused in assertions) would show how many times
 * the action method itself ran if that race is hit.
 */
class ConcurrentStepExecutionTest extends AbstractIntegrationTest {

    public static class SoloCtx implements SagaContext {
        private int marker = 0;
        public SoloCtx() {}
        public int getMarker() { return marker; }
        public void setMarker(int v) { this.marker = v; }
    }

    private static final String SOLO_TYPE = "concurrent-step-solo";
    private static final ConcurrentHashMap<String, ExecutionResult> participantCache = new ConcurrentHashMap<>();
    private static final AtomicInteger actualExecutionCount = new AtomicInteger(0);

    @Autowired ForwardExecutionEngine forwardEngine;
    @Autowired SagaDefinitionRegistry registry;
    @Autowired SagaInstanceRepository sagaRepo;
    @Autowired SagaStepExecutionRepository stepRepo;
    @Autowired ObjectMapper objectMapper;

    private static boolean registered = false;

    @BeforeEach
    void registerSoloSaga() {
        participantCache.clear();
        actualExecutionCount.set(0);
        if (registered) return;
        registered = true;

        SagaDefinition<SoloCtx> solo = SagaDefinition.named(SOLO_TYPE, SoloCtx.class)
                .retryableStep("solo-step")
                    .action((ctx, key) -> participantCache.computeIfAbsent(key, k -> {
                        actualExecutionCount.incrementAndGet();
                        return new ExecutionResult.Success(null);
                    }))
                .build();
        registry.register(solo);
    }

    @AfterEach
    void cleanup() {
        stepRepo.deleteAll();
        sagaRepo.deleteAll();
    }

    @Test
    void concurrentThreads_sameStepAndAttempt_exactlyOneIntentAndOneExecution() throws Exception {
        SagaDefinition<?> definition = registry.lookup(SOLO_TYPE);
        String ctxJson = objectMapper.writeValueAsString(new SoloCtx());

        SagaInstance saga = sagaRepo.save(SagaInstance.create(SOLO_TYPE, ctxJson));
        saga.setStatus(SagaStatus.EXECUTING);
        saga = sagaRepo.save(saga);
        UUID sagaId = saga.getId();

        int threadCount = 10;
        CyclicBarrier barrier = new CyclicBarrier(threadCount);
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            SagaInstance sagaForThread = saga;
            futures.add(pool.submit(() -> {
                barrier.await();
                forwardEngine.executeFromStep(sagaForThread, definition, 0);
                return null;
            }));
        }
        for (Future<?> f : futures) {
            f.get(10, TimeUnit.SECONDS);
        }
        pool.shutdown();

        List<SagaStepExecution> rows = stepRepo.findBySagaIdOrderByStepIndexAscAttemptNumberAsc(sagaId);
        assertThat(rows)
                .as("exactly one intent row must have been persisted despite %d racing threads", threadCount)
                .hasSize(1);
        assertThat(rows.get(0).getStatus()).isEqualTo(StepExecutionStatus.COMPLETED);

        assertThat(actualExecutionCount.get())
                .as("the participant must see at most one real execution for this key, " +
                        "regardless of how many times the engine called it")
                .isEqualTo(1);

        assertThat(sagaRepo.findById(sagaId).orElseThrow().getStatus())
                .isEqualTo(SagaStatus.COMPLETED);
    }
}
