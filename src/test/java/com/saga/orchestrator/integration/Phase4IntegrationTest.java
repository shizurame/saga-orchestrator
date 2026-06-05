package com.saga.orchestrator.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.saga.orchestrator.definition.*;
import com.saga.orchestrator.engine.SagaOrchestrator;
import com.saga.orchestrator.persistence.entity.SagaInstance;
import com.saga.orchestrator.persistence.entity.SagaStepExecution;
import com.saga.orchestrator.persistence.repository.CompensationStepRepository;
import com.saga.orchestrator.persistence.repository.SagaEntityLockRepository;
import com.saga.orchestrator.persistence.repository.SagaInstanceRepository;
import com.saga.orchestrator.persistence.repository.SagaStepExecutionRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

class Phase4IntegrationTest extends AbstractIntegrationTest {

    // ── Test saga context ─────────────────────────────────────────────────────

    public static class TestCtx implements SagaContext {
        private int stepsRun = 0;
        public TestCtx() {}
        public int getStepsRun() { return stepsRun; }
        public void setStepsRun(int v) { this.stepsRun = v; }
    }

    // ── Spring beans ──────────────────────────────────────────────────────────

    @Autowired SagaOrchestrator           orchestrator;
    @Autowired SagaDefinitionRegistry     registry;
    @Autowired SagaInstanceRepository     sagaRepo;
    @Autowired SagaStepExecutionRepository stepRepo;
    @Autowired SagaEntityLockRepository   lockRepo;
    @Autowired CompensationStepRepository compensationStepRepo;
    @Autowired ObjectMapper               objectMapper;

    // ── Saga type names (unique per test class to survive context caching) ────

    private static final String HAPPY_PATH_TYPE = "phase4-happy-path";
    private static final String RETRY_TYPE      = "phase4-retry";

    private static boolean registered = false;

    @BeforeEach
    void registerTestSagas() {
        if (registered) return;
        registered = true;

        // 3-step happy-path saga: all COMPENSATABLE, bean actions, no retry
        SagaDefinition<TestCtx> happyPath = SagaDefinition.named(HAPPY_PATH_TYPE, TestCtx.class)
            .compensatableStep("step-0")
                .action((ctx, key) -> { ctx.setStepsRun(ctx.getStepsRun() + 1); return new ExecutionResult.Success(null); })
                .compensation((ctx, key) -> new ExecutionResult.Success(null))
                .timeout(Duration.ofSeconds(5), TimeoutMode.RETRY_WITH_IDEMPOTENCY)
            .compensatableStep("step-1")
                .action((ctx, key) -> { ctx.setStepsRun(ctx.getStepsRun() + 1); return new ExecutionResult.Success(null); })
                .compensation((ctx, key) -> new ExecutionResult.Success(null))
                .timeout(Duration.ofSeconds(5), TimeoutMode.RETRY_WITH_IDEMPOTENCY)
            .compensatableStep("step-2")
                .action((ctx, key) -> { ctx.setStepsRun(ctx.getStepsRun() + 1); return new ExecutionResult.Success(null); })
                .compensation((ctx, key) -> new ExecutionResult.Success(null))
                .timeout(Duration.ofSeconds(5), TimeoutMode.RETRY_WITH_IDEMPOTENCY)
            .build();
        registry.register(happyPath);
    }

    @AfterEach
    void cleanup() {
        compensationStepRepo.deleteAll();
        lockRepo.deleteAll();
        stepRepo.deleteAll();
        sagaRepo.deleteAll();
    }

    // ── Test 1: Happy path ────────────────────────────────────────────────────

    @Test
    void happyPath_3StepSaga_completesSuccessfully() throws Exception {
        String ctxJson = objectMapper.writeValueAsString(new TestCtx());

        UUID sagaId = orchestrator.startSaga(HAPPY_PATH_TYPE, ctxJson, List.of());

        // Saga should be COMPLETED
        SagaInstance saga = sagaRepo.findById(sagaId).orElseThrow();
        assertThat(saga.getStatus()).isEqualTo(SagaStatus.COMPLETED);
        assertThat(saga.getCurrentStep()).isEqualTo(3); // advanced past all 3 steps

        // Exactly 3 step execution records, all COMPLETED
        List<SagaStepExecution> steps =
            stepRepo.findBySagaIdOrderByStepIndexAscAttemptNumberAsc(sagaId);
        assertThat(steps).hasSize(3);
        steps.forEach(s -> assertThat(s.getStatus()).isEqualTo(StepExecutionStatus.COMPLETED));
        assertThat(steps.get(0).getStepIndex()).isEqualTo(0);
        assertThat(steps.get(1).getStepIndex()).isEqualTo(1);
        assertThat(steps.get(2).getStepIndex()).isEqualTo(2);

        // Each step has a unique idempotency key (same attempt=1, different stepIndex)
        long uniqueKeys = steps.stream().map(SagaStepExecution::getIdempotencyKey).distinct().count();
        assertThat(uniqueKeys).isEqualTo(3);

        // Context was updated: stepsRun == 3
        TestCtx finalCtx = objectMapper.readValue(saga.getContext(), TestCtx.class);
        assertThat(finalCtx.getStepsRun()).isEqualTo(3);
    }

    // ── Test 2: Retry on failure ──────────────────────────────────────────────

    @Test
    void retry_stepFailsOnFirstAttempt_succeedsOnSecond() throws Exception {
        AtomicInteger callCount = new AtomicInteger(0);

        // Retry saga: 1 step, retryPolicy allows 2 attempts, 0-delay
        SagaDefinition<TestCtx> retrySaga = SagaDefinition.named(RETRY_TYPE, TestCtx.class)
            .compensatableStep("flaky-step")
                .action((ctx, key) -> {
                    if (callCount.incrementAndGet() == 1) {
                        return new ExecutionResult.Failure("transient error");
                    }
                    return new ExecutionResult.Success("recovered");
                })
                .compensation((ctx, key) -> new ExecutionResult.Success(null))
                .timeout(Duration.ofSeconds(5), TimeoutMode.RETRY_WITH_IDEMPOTENCY)
                .retryPolicy(RetryPolicy.fixed(2, Duration.ZERO))
            .build();

        if (!registry.isRegistered(RETRY_TYPE)) {
            registry.register(retrySaga);
        }

        String ctxJson = objectMapper.writeValueAsString(new TestCtx());
        UUID sagaId = orchestrator.startSaga(RETRY_TYPE, ctxJson, List.of());

        // Saga completed despite first failure
        SagaInstance saga = sagaRepo.findById(sagaId).orElseThrow();
        assertThat(saga.getStatus()).isEqualTo(SagaStatus.COMPLETED);

        // Two step execution records: attempt 1 (FAILED) and attempt 2 (COMPLETED)
        List<SagaStepExecution> executions =
            stepRepo.findBySagaIdOrderByStepIndexAscAttemptNumberAsc(sagaId);
        assertThat(executions).hasSize(2);

        SagaStepExecution first  = executions.get(0);
        SagaStepExecution second = executions.get(1);
        assertThat(first.getAttemptNumber()).isEqualTo(1);
        assertThat(first.getStatus()).isEqualTo(StepExecutionStatus.FAILED);
        assertThat(second.getAttemptNumber()).isEqualTo(2);
        assertThat(second.getStatus()).isEqualTo(StepExecutionStatus.COMPLETED);

        // Attempt 2 has a DIFFERENT idempotency key (attempt_number changed)
        assertThat(first.getIdempotencyKey()).isNotEqualTo(second.getIdempotencyKey());
    }

    // ── Test 3: OCC — duplicate step intent ───────────────────────────────────

    @Test
    void occ_duplicateStepIntent_secondInsertThrowsConstraintViolation() throws Exception {
        // Simulate two nodes both trying to start the same step (sagaId, stepIndex=0, attempt=1).
        // The unique constraint uq_step_attempt catches the second insert.
        SagaInstance saga = sagaRepo.save(SagaInstance.create("fund-transfer", "{}"));
        UUID sagaId = saga.getId();
        Instant timeout = Instant.now().plusSeconds(30);

        // First executor wins
        SagaStepExecution first = SagaStepExecution.createInProgress(sagaId, 0, 1, "key-occ-001", timeout);
        stepRepo.saveAndFlush(first);

        // Second executor races with same (sagaId, stepIndex=0, attempt=1) — must fail
        SagaStepExecution duplicate = SagaStepExecution.createInProgress(sagaId, 0, 1, "key-occ-002", timeout);
        assertThatThrownBy(() -> stepRepo.saveAndFlush(duplicate))
                .isInstanceOf(DataIntegrityViolationException.class);

        // Exactly one step execution record persisted
        List<SagaStepExecution> executions =
            stepRepo.findBySagaIdOrderByStepIndexAscAttemptNumberAsc(sagaId);
        assertThat(executions).hasSize(1);
        assertThat(executions.get(0).getIdempotencyKey()).isEqualTo("key-occ-001");
    }
}
