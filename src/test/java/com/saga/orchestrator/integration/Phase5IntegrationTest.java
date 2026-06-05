package com.saga.orchestrator.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.saga.orchestrator.definition.*;
import com.saga.orchestrator.engine.CompensationEngine;
import com.saga.orchestrator.engine.SagaOrchestrator;
import com.saga.orchestrator.persistence.entity.CompensationStep;
import com.saga.orchestrator.persistence.entity.SagaInstance;
import com.saga.orchestrator.persistence.repository.CompensationStepRepository;
import com.saga.orchestrator.persistence.repository.SagaEntityLockRepository;
import com.saga.orchestrator.persistence.repository.SagaInstanceRepository;
import com.saga.orchestrator.persistence.repository.SagaStepExecutionRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class Phase5IntegrationTest extends AbstractIntegrationTest {

    // ── Test saga context ─────────────────────────────────────────────────────

    public static class TestCtx implements SagaContext {
        private int stepsRun = 0;
        public TestCtx() {}
        public int getStepsRun()          { return stepsRun; }
        public void setStepsRun(int v)    { this.stepsRun = v; }
    }

    // ── Spring beans ──────────────────────────────────────────────────────────

    @Autowired SagaOrchestrator           orchestrator;
    @Autowired CompensationEngine         compensationEngine;
    @Autowired SagaDefinitionRegistry     registry;
    @Autowired SagaInstanceRepository     sagaRepo;
    @Autowired SagaStepExecutionRepository stepRepo;
    @Autowired CompensationStepRepository  compStepRepo;
    @Autowired SagaEntityLockRepository   lockRepo;
    @Autowired ObjectMapper               objectMapper;

    // ── Saga type names (unique to this test class) ────────────────────────

    private static final String THREE_COMP_FAIL_LAST = "phase5-three-comp-fail-last";
    private static final String CRASH_RESUME_TYPE    = "phase5-crash-resume";
    private static final String ALL_COMPED_RESUME    = "phase5-all-comped-resume";
    private static final String PIVOT_FAIL_TYPE      = "phase5-pivot-fail";
    private static final String NO_COMP_TYPE         = "phase5-no-comp";
    private static final String COMP_RETRY_TYPE      = "phase5-comp-retry";

    // Static so lambdas registered on the first JUnit instance write to the same
    // objects that each test's assertions read (JUnit creates a new instance per test
    // method, but the Spring-registered lambdas capture the original instance's reference).
    private static final List<Integer> compensationOrder    = new CopyOnWriteArrayList<>();
    private static final AtomicInteger crashResumeCompCount = new AtomicInteger(0);
    private static final AtomicInteger allCompedCompCount   = new AtomicInteger(0);
    private static final AtomicInteger compRetryCount       = new AtomicInteger(0);

    private static boolean registered = false;

    @BeforeEach
    void registerTestSagas() {
        compensationOrder.clear();
        crashResumeCompCount.set(0);
        allCompedCompCount.set(0);
        compRetryCount.set(0);

        if (registered) return;
        registered = true;

        // Saga 1: 3 COMPENSATABLE steps; step 2 always fails → comps for 1 and 0
        SagaDefinition<TestCtx> threeCompFailLast = SagaDefinition.named(THREE_COMP_FAIL_LAST, TestCtx.class)
            .compensatableStep("comp-step-0")
                .action((ctx, key) -> { ctx.setStepsRun(ctx.getStepsRun() + 1); return new ExecutionResult.Success(null); })
                .compensation((ctx, key) -> { compensationOrder.add(0); return new ExecutionResult.Success(null); })
                .timeout(Duration.ofSeconds(5), TimeoutMode.RETRY_WITH_IDEMPOTENCY)
            .compensatableStep("comp-step-1")
                .action((ctx, key) -> { ctx.setStepsRun(ctx.getStepsRun() + 1); return new ExecutionResult.Success(null); })
                .compensation((ctx, key) -> { compensationOrder.add(1); return new ExecutionResult.Success(null); })
                .timeout(Duration.ofSeconds(5), TimeoutMode.RETRY_WITH_IDEMPOTENCY)
            .compensatableStep("comp-step-2")
                .action((ctx, key) -> new ExecutionResult.Failure("always fails"))
                .compensation((ctx, key) -> { compensationOrder.add(2); return new ExecutionResult.Success(null); })
                .timeout(Duration.ofSeconds(5), TimeoutMode.RETRY_WITH_IDEMPOTENCY)
            .build();
        registry.register(threeCompFailLast);

        // Saga 2: same shape as saga 1, used for the crash-resume test
        SagaDefinition<TestCtx> crashResume = SagaDefinition.named(CRASH_RESUME_TYPE, TestCtx.class)
            .compensatableStep("cr-step-0")
                .action((ctx, key) -> new ExecutionResult.Success(null))
                .compensation((ctx, key) -> { crashResumeCompCount.incrementAndGet(); return new ExecutionResult.Success(null); })
                .timeout(Duration.ofSeconds(5), TimeoutMode.RETRY_WITH_IDEMPOTENCY)
            .compensatableStep("cr-step-1")
                .action((ctx, key) -> new ExecutionResult.Success(null))
                .compensation((ctx, key) -> new ExecutionResult.Success(null))
                .timeout(Duration.ofSeconds(5), TimeoutMode.RETRY_WITH_IDEMPOTENCY)
            .compensatableStep("cr-step-2")
                .action((ctx, key) -> new ExecutionResult.Failure("fail"))
                .compensation((ctx, key) -> new ExecutionResult.Success(null))
                .timeout(Duration.ofSeconds(5), TimeoutMode.RETRY_WITH_IDEMPOTENCY)
            .build();
        registry.register(crashResume);

        // Saga 3: same shape, used for all-already-compensated recovery test
        SagaDefinition<TestCtx> allCompedResume = SagaDefinition.named(ALL_COMPED_RESUME, TestCtx.class)
            .compensatableStep("ac-step-0")
                .action((ctx, key) -> new ExecutionResult.Success(null))
                .compensation((ctx, key) -> { allCompedCompCount.incrementAndGet(); return new ExecutionResult.Success(null); })
                .timeout(Duration.ofSeconds(5), TimeoutMode.RETRY_WITH_IDEMPOTENCY)
            .compensatableStep("ac-step-1")
                .action((ctx, key) -> new ExecutionResult.Failure("fail"))
                .compensation((ctx, key) -> new ExecutionResult.Success(null))
                .timeout(Duration.ofSeconds(5), TimeoutMode.RETRY_WITH_IDEMPOTENCY)
            .build();
        registry.register(allCompedResume);

        // Saga 4: COMPENSATABLE then PIVOT; PIVOT always fails
        SagaDefinition<TestCtx> pivotFail = SagaDefinition.named(PIVOT_FAIL_TYPE, TestCtx.class)
            .compensatableStep("pf-comp-step")
                .action((ctx, key) -> new ExecutionResult.Success(null))
                .compensation((ctx, key) -> { compensationOrder.add(0); return new ExecutionResult.Success(null); })
                .timeout(Duration.ofSeconds(5), TimeoutMode.RETRY_WITH_IDEMPOTENCY)
            .pivotStep("pf-pivot-step")
                .action((ctx, key) -> new ExecutionResult.Failure("pivot fails"))
                .timeout(Duration.ofSeconds(5), TimeoutMode.RETRY_WITH_IDEMPOTENCY)
            .build();
        registry.register(pivotFail);

        // Saga 5: PIVOT step only; fails → no prior compensatables → FAILED
        SagaDefinition<TestCtx> noComp = SagaDefinition.named(NO_COMP_TYPE, TestCtx.class)
            .pivotStep("nc-pivot-step")
                .action((ctx, key) -> new ExecutionResult.Failure("pivot fails"))
                .timeout(Duration.ofSeconds(5), TimeoutMode.RETRY_WITH_IDEMPOTENCY)
            .build();
        registry.register(noComp);

        // Saga 6: 2 COMPENSATABLE steps; step 1 fails; step 0's comp fails on first attempt,
        //          succeeds on second — tests compensation retry
        SagaDefinition<TestCtx> compRetry = SagaDefinition.named(COMP_RETRY_TYPE, TestCtx.class)
            .compensatableStep("cr-comp-step-0")
                .action((ctx, key) -> new ExecutionResult.Success(null))
                .compensation((ctx, key) -> {
                    int n = compRetryCount.incrementAndGet();
                    if (n == 1) return new ExecutionResult.Failure("comp transient error");
                    return new ExecutionResult.Success("comp recovered");
                })
                .timeout(Duration.ofSeconds(5), TimeoutMode.RETRY_WITH_IDEMPOTENCY)
                .retryPolicy(RetryPolicy.fixed(2, Duration.ZERO))
            .compensatableStep("cr-comp-step-1")
                .action((ctx, key) -> new ExecutionResult.Failure("step 1 fails"))
                .compensation((ctx, key) -> new ExecutionResult.Success(null))
                .timeout(Duration.ofSeconds(5), TimeoutMode.RETRY_WITH_IDEMPOTENCY)
            .build();
        registry.register(compRetry);
    }

    @AfterEach
    void cleanup() {
        compStepRepo.deleteAll();
        stepRepo.deleteAll();
        lockRepo.deleteAll();
        sagaRepo.deleteAll();
    }

    // ── Test 1: step 2 fails, compensates steps 1 and 0 in reverse order ────

    @Test
    void step2Fails_compensatesSteps1And0_inReverseOrder() throws Exception {
        String ctxJson = objectMapper.writeValueAsString(new TestCtx());
        UUID sagaId = orchestrator.startSaga(THREE_COMP_FAIL_LAST, ctxJson, List.of());

        SagaInstance saga = sagaRepo.findById(sagaId).orElseThrow();
        assertThat(saga.getStatus()).isEqualTo(SagaStatus.COMPENSATED);

        // Compensation executed in reverse: step 1 before step 0
        assertThat(compensationOrder).containsExactly(1, 0);

        // Two compensation records in DB, both COMPLETED
        List<CompensationStep> comps = compStepRepo.findBySagaIdOrderByStepIndexAsc(sagaId);
        assertThat(comps).hasSize(2);
        assertThat(comps.get(0).getStepIndex()).isEqualTo(0);
        assertThat(comps.get(0).getStatus()).isEqualTo(CompensationStatus.COMPLETED);
        assertThat(comps.get(1).getStepIndex()).isEqualTo(1);
        assertThat(comps.get(1).getStatus()).isEqualTo(CompensationStatus.COMPLETED);

        // Compensation for step 2 must NOT have been created (step 2 failed, was never completed)
        assertThat(comps.stream().anyMatch(c -> c.getStepIndex() == 2)).isFalse();

        // Entity locks released
        assertThat(lockRepo.findAll()).isEmpty();
    }

    // ── Test 2: crash during compensation → recovery resumes from last PENDING ─

    @Test
    void crashDuringCompensation_recoveryResumesFromLastPending() throws Exception {
        // Arrange: simulate a state where compensation for step 1 completed but
        // step 0 is still PENDING (orchestrator crashed after writing the plan entry
        // for step 0 but before executing it).
        String ctxJson = objectMapper.writeValueAsString(new TestCtx());

        // Use CRASH_RESUME_TYPE definition which tracks step 0 compensation calls
        SagaDefinition<?> def = registry.lookup(CRASH_RESUME_TYPE);

        // Create saga instance directly in COMPENSATING status
        SagaInstance saga = sagaRepo.save(SagaInstance.create(CRASH_RESUME_TYPE, ctxJson));
        saga.setStatus(SagaStatus.COMPENSATING);
        saga = sagaRepo.save(saga);
        UUID sagaId = saga.getId();

        // Insert comp plan manually: step 1 already COMPLETED, step 0 still PENDING
        com.saga.orchestrator.infrastructure.IdempotencyKeyGenerator kg =
                new com.saga.orchestrator.infrastructure.IdempotencyKeyGenerator();
        CompensationStep step1Comp = CompensationStep.pending(sagaId, 1, "cr-step-1", kg.compensationKey(sagaId, 1, 0));
        step1Comp.markInProgress();
        step1Comp.markCompleted("null");
        CompensationStep step0Comp = CompensationStep.pending(sagaId, 0, "cr-step-0", kg.compensationKey(sagaId, 0, 0));

        compStepRepo.saveAll(List.of(step0Comp, step1Comp));

        // Act: call compensate (simulates recovery scanner calling resumeSaga)
        compensationEngine.compensate(saga, def);

        // Assert: only step 0's compensation ran (step 1 was already COMPLETED → not in PENDING query)
        assertThat(crashResumeCompCount.get()).isEqualTo(1);

        SagaInstance result = sagaRepo.findById(sagaId).orElseThrow();
        assertThat(result.getStatus()).isEqualTo(SagaStatus.COMPENSATED);

        CompensationStep finalStep0 = compStepRepo.findById(step0Comp.getId()).orElseThrow();
        assertThat(finalStep0.getStatus()).isEqualTo(CompensationStatus.COMPLETED);
    }

    // ── Test 3: all compensations already done → idempotent terminal transition

    @Test
    void allCompensationsAlreadyDone_compensateIsIdempotent() throws Exception {
        // Simulate crash-after-all-comps-but-before-saga-transition:
        // Saga status is COMPENSATING, all comp steps are COMPLETED.
        // Calling compensate() should transition to COMPENSATED without re-running any action.
        String ctxJson = objectMapper.writeValueAsString(new TestCtx());
        SagaDefinition<?> def = registry.lookup(ALL_COMPED_RESUME);

        SagaInstance saga = sagaRepo.save(SagaInstance.create(ALL_COMPED_RESUME, ctxJson));
        saga.setStatus(SagaStatus.COMPENSATING);
        saga = sagaRepo.save(saga);
        UUID sagaId = saga.getId();

        com.saga.orchestrator.infrastructure.IdempotencyKeyGenerator kg =
                new com.saga.orchestrator.infrastructure.IdempotencyKeyGenerator();
        CompensationStep cs = CompensationStep.pending(sagaId, 0, "ac-step-0", kg.compensationKey(sagaId, 0, 0));
        cs.markInProgress();
        cs.markCompleted("null");
        compStepRepo.save(cs);

        // Act: compensate with no PENDING steps
        compensationEngine.compensate(saga, def);

        // No compensation action should have run (everything was already COMPLETED)
        assertThat(allCompedCompCount.get()).isEqualTo(0);

        // Saga transitioned to COMPENSATED
        assertThat(sagaRepo.findById(sagaId).orElseThrow().getStatus())
                .isEqualTo(SagaStatus.COMPENSATED);
    }

    // ── Test 4: PIVOT failure → prior COMPENSATABLE step is compensated ──────

    @Test
    void pivotFails_priorCompensatableStepIsCompensated() throws Exception {
        String ctxJson = objectMapper.writeValueAsString(new TestCtx());
        UUID sagaId = orchestrator.startSaga(PIVOT_FAIL_TYPE, ctxJson, List.of());

        SagaInstance saga = sagaRepo.findById(sagaId).orElseThrow();
        assertThat(saga.getStatus()).isEqualTo(SagaStatus.COMPENSATED);

        // PIVOT step (index 1) failed — only COMPENSATABLE step (index 0) gets a comp record
        List<CompensationStep> comps = compStepRepo.findBySagaIdOrderByStepIndexAsc(sagaId);
        assertThat(comps).hasSize(1);
        assertThat(comps.get(0).getStepIndex()).isEqualTo(0);
        assertThat(comps.get(0).getStatus()).isEqualTo(CompensationStatus.COMPLETED);

        // Compensation action ran exactly once
        assertThat(compensationOrder).containsExactly(0);
    }

    // ── Test 5: PIVOT failure with no prior COMPENSATABLE steps → FAILED ─────

    @Test
    void pivotFails_noCompensatables_sagaTransitionsToFailed() throws Exception {
        String ctxJson = objectMapper.writeValueAsString(new TestCtx());
        UUID sagaId = orchestrator.startSaga(NO_COMP_TYPE, ctxJson, List.of());

        SagaInstance saga = sagaRepo.findById(sagaId).orElseThrow();
        assertThat(saga.getStatus()).isEqualTo(SagaStatus.FAILED);

        // No compensation plan created
        assertThat(compStepRepo.findBySagaIdOrderByStepIndexAsc(sagaId)).isEmpty();
    }

    // ── Test 6: compensation retries on transient failure ────────────────────

    @Test
    void compensationRetry_succeedsOnSecondAttempt() throws Exception {
        String ctxJson = objectMapper.writeValueAsString(new TestCtx());
        UUID sagaId = orchestrator.startSaga(COMP_RETRY_TYPE, ctxJson, List.of());

        SagaInstance saga = sagaRepo.findById(sagaId).orElseThrow();
        assertThat(saga.getStatus()).isEqualTo(SagaStatus.COMPENSATED);

        // step 0's compensation ran twice (failed on attempt 1, succeeded on attempt 2)
        assertThat(compRetryCount.get()).isEqualTo(2);

        // CompensationStep for index 0 is COMPLETED despite the transient failure
        List<CompensationStep> comps = compStepRepo.findBySagaIdOrderByStepIndexAsc(sagaId);
        assertThat(comps).hasSize(1); // only step 0 compensated (step 1 never succeeded)
        assertThat(comps.get(0).getStepIndex()).isEqualTo(0);
        assertThat(comps.get(0).getStatus()).isEqualTo(CompensationStatus.COMPLETED);
        assertThat(comps.get(0).getAttemptNumber()).isEqualTo(2); // incremented twice
    }
}
