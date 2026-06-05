package com.saga.orchestrator.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.saga.orchestrator.definition.*;
import com.saga.orchestrator.definition.HttpStepAction;
import com.saga.orchestrator.engine.SagaOrchestrator;
import com.saga.orchestrator.persistence.entity.CompensationStep;
import com.saga.orchestrator.persistence.entity.SagaInstance;
import com.saga.orchestrator.persistence.entity.SagaStepExecution;
import com.saga.orchestrator.persistence.repository.CompensationStepRepository;
import com.saga.orchestrator.persistence.repository.SagaEntityLockRepository;
import com.saga.orchestrator.persistence.repository.SagaInstanceRepository;
import com.saga.orchestrator.persistence.repository.SagaStepExecutionRepository;
import com.saga.orchestrator.scanning.OrphanedSagaScanService;
import com.saga.orchestrator.scanning.TimeoutScanService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class Phase6IntegrationTest extends AbstractIntegrationTest {

    // ── Test saga context ─────────────────────────────────────────────────────

    public static class TestCtx implements SagaContext {
        private int stepsRun = 0;
        public TestCtx() {}
        public int getStepsRun()       { return stepsRun; }
        public void setStepsRun(int v) { this.stepsRun = v; }
    }

    // ── Spring beans ──────────────────────────────────────────────────────────

    @Autowired SagaOrchestrator           orchestrator;
    @Autowired SagaDefinitionRegistry     registry;
    @Autowired SagaInstanceRepository     sagaRepo;
    @Autowired SagaStepExecutionRepository stepRepo;
    @Autowired CompensationStepRepository  compStepRepo;
    @Autowired SagaEntityLockRepository   lockRepo;
    @Autowired TimeoutScanService         timeoutScanService;
    @Autowired OrphanedSagaScanService    orphanedSagaScanService;
    @Autowired ObjectMapper               objectMapper;

    @Value("${saga.orchestrator.node-id}")
    String nodeId;

    // ── Saga type names ────────────────────────────────────────────────────────

    private static final String RETRY_IDEMPOTENCY_TYPE = "phase6-retry-idempotency";
    private static final String TREAT_AS_FAILURE_TYPE  = "phase6-treat-as-failure";
    private static final String WAIT_FOR_CALLBACK_TYPE = "phase6-wait-for-callback";
    private static final String CRASH_RECOVERY_TYPE    = "phase6-crash-recovery";

    // Static tracking state (avoids JUnit per-instance lambda capture issue)
    private static final AtomicInteger retryIdempotencyCallCount   = new AtomicInteger(0);
    private static final AtomicInteger treatAsFailureCompCount      = new AtomicInteger(0);
    private static final AtomicInteger waitForCallbackStep1RunCount = new AtomicInteger(0);
    private static final AtomicInteger crashRecoveryStep2RunCount   = new AtomicInteger(0);

    private static boolean registered = false;

    @BeforeEach
    void registerTestSagas() {
        retryIdempotencyCallCount.set(0);
        treatAsFailureCompCount.set(0);
        waitForCallbackStep1RunCount.set(0);
        crashRecoveryStep2RunCount.set(0);

        if (registered) return;
        registered = true;

        // Saga 1: RETRY_WITH_IDEMPOTENCY — step 0 returns TimedOut on first call, Success on second
        // (simulates participant that was slow but eventually responds with cached result)
        SagaDefinition<TestCtx> retryIdempotency = SagaDefinition.named(RETRY_IDEMPOTENCY_TYPE, TestCtx.class)
            .compensatableStep("ri-step-0")
                .action((ctx, key) -> {
                    int n = retryIdempotencyCallCount.incrementAndGet();
                    if (n == 1) return ExecutionResult.TimedOut.INSTANCE; // times out on first call
                    return new ExecutionResult.Success("cached-result"); // returns cached on retry
                })
                .compensation((ctx, key) -> new ExecutionResult.Success(null))
                .timeout(Duration.ofMillis(1), TimeoutMode.RETRY_WITH_IDEMPOTENCY) // very short timeout
            .compensatableStep("ri-step-1")
                .action((ctx, key) -> { ctx.setStepsRun(ctx.getStepsRun() + 1); return new ExecutionResult.Success(null); })
                .compensation((ctx, key) -> new ExecutionResult.Success(null))
                .timeout(Duration.ofSeconds(5), TimeoutMode.RETRY_WITH_IDEMPOTENCY)
            .build();
        registry.register(retryIdempotency);

        // Saga 2: TREAT_AS_FAILURE — step 0 (COMPENSATABLE) succeeds, step 1 times out with TREAT_AS_FAILURE
        SagaDefinition<TestCtx> treatAsFailure = SagaDefinition.named(TREAT_AS_FAILURE_TYPE, TestCtx.class)
            .compensatableStep("taf-step-0")
                .action((ctx, key) -> new ExecutionResult.Success(null))
                .compensation((ctx, key) -> { treatAsFailureCompCount.incrementAndGet(); return new ExecutionResult.Success(null); })
                .timeout(Duration.ofSeconds(5), TimeoutMode.RETRY_WITH_IDEMPOTENCY)
            .compensatableStep("taf-step-1")
                .action((ctx, key) -> ExecutionResult.TimedOut.INSTANCE)
                .compensation((ctx, key) -> new ExecutionResult.Success(null))
                .timeout(Duration.ofMillis(1), TimeoutMode.TREAT_AS_FAILURE)
            .build();
        registry.register(treatAsFailure);

        // Saga 3: WAIT_FOR_CALLBACK — step 0 (COMPENSATABLE) succeeds, step 1 times out with WAIT_FOR_CALLBACK,
        //          then step 2 succeeds after callback resumes execution.
        // Step 1 must be an HttpStepAction — DSL enforces this at build time.
        HttpStepAction<TestCtx> wfcStepAction = (ctx, key) -> ExecutionResult.TimedOut.INSTANCE;
        SagaDefinition<TestCtx> waitForCallback = SagaDefinition.named(WAIT_FOR_CALLBACK_TYPE, TestCtx.class)
            .compensatableStep("wfc-step-0")
                .action((ctx, key) -> new ExecutionResult.Success(null))
                .compensation((ctx, key) -> new ExecutionResult.Success(null))
                .timeout(Duration.ofSeconds(5), TimeoutMode.RETRY_WITH_IDEMPOTENCY)
            .compensatableStep("wfc-step-1")
                .action(wfcStepAction)
                .compensation((ctx, key) -> new ExecutionResult.Success(null))
                .timeout(Duration.ofMillis(1), TimeoutMode.WAIT_FOR_CALLBACK)
            .compensatableStep("wfc-step-2")
                .action((ctx, key) -> { waitForCallbackStep1RunCount.incrementAndGet(); return new ExecutionResult.Success(null); })
                .compensation((ctx, key) -> new ExecutionResult.Success(null))
                .timeout(Duration.ofSeconds(5), TimeoutMode.RETRY_WITH_IDEMPOTENCY)
            .build();
        registry.register(waitForCallback);

        // Saga 4: crash recovery — 3 steps, all succeed; used to test orphan scanner
        SagaDefinition<TestCtx> crashRecovery = SagaDefinition.named(CRASH_RECOVERY_TYPE, TestCtx.class)
            .compensatableStep("cr-step-0")
                .action((ctx, key) -> { ctx.setStepsRun(ctx.getStepsRun() + 1); return new ExecutionResult.Success(null); })
                .compensation((ctx, key) -> new ExecutionResult.Success(null))
                .timeout(Duration.ofSeconds(5), TimeoutMode.RETRY_WITH_IDEMPOTENCY)
            .compensatableStep("cr-step-1")
                .action((ctx, key) -> { ctx.setStepsRun(ctx.getStepsRun() + 1); return new ExecutionResult.Success(null); })
                .compensation((ctx, key) -> new ExecutionResult.Success(null))
                .timeout(Duration.ofSeconds(5), TimeoutMode.RETRY_WITH_IDEMPOTENCY)
            .compensatableStep("cr-step-2")
                .action((ctx, key) -> {
                    crashRecoveryStep2RunCount.incrementAndGet();
                    return new ExecutionResult.Success(null);
                })
                .compensation((ctx, key) -> new ExecutionResult.Success(null))
                .timeout(Duration.ofSeconds(5), TimeoutMode.RETRY_WITH_IDEMPOTENCY)
            .build();
        registry.register(crashRecovery);
    }

    @AfterEach
    void cleanup() {
        compStepRepo.deleteAll();
        stepRepo.deleteAll();
        lockRepo.deleteAll();
        sagaRepo.deleteAll();
    }

    // ── Test 1: RETRY_WITH_IDEMPOTENCY ────────────────────────────────────────

    @Test
    void timeout_retryWithIdempotency_retrySucceeds_sagaCompletes() throws Exception {
        String ctxJson = objectMapper.writeValueAsString(new TestCtx());

        // After startSaga, step 0 returns TimedOut → left IN_PROGRESS, engine stops.
        // Saga is EXECUTING, step_execution for step 0 is IN_PROGRESS.
        UUID sagaId = orchestrator.startSaga(RETRY_IDEMPOTENCY_TYPE, ctxJson, List.of());

        SagaInstance saga = sagaRepo.findById(sagaId).orElseThrow();
        assertThat(saga.getStatus()).isEqualTo(SagaStatus.EXECUTING);

        // The step_execution for step 0 should be IN_PROGRESS (not TIMED_OUT)
        List<SagaStepExecution> execs = stepRepo.findBySagaIdOrderByStepIndexAscAttemptNumberAsc(sagaId);
        assertThat(execs).hasSize(1);
        assertThat(execs.get(0).getStatus()).isEqualTo(StepExecutionStatus.IN_PROGRESS);
        assertThat(execs.get(0).getStepIndex()).isEqualTo(0);

        // TimeoutScanner fires — finds the IN_PROGRESS step, retries with same key
        // Second call returns Success (simulates participant returning cached result)
        timeoutScanService.scan();

        // After scanner retry: step 0 COMPLETED, step 1 executes and also COMPLETED
        saga = sagaRepo.findById(sagaId).orElseThrow();
        assertThat(saga.getStatus()).isEqualTo(SagaStatus.COMPLETED);

        // Step 0 was retried with the SAME idempotency key — verify action was called twice total
        assertThat(retryIdempotencyCallCount.get()).isEqualTo(2);

        // Two step_execution records: step 0 (COMPLETED) and step 1 (COMPLETED)
        execs = stepRepo.findBySagaIdOrderByStepIndexAscAttemptNumberAsc(sagaId);
        assertThat(execs).hasSize(2);
        assertThat(execs.get(0).getStepIndex()).isEqualTo(0);
        assertThat(execs.get(0).getStatus()).isEqualTo(StepExecutionStatus.COMPLETED);
        assertThat(execs.get(1).getStepIndex()).isEqualTo(1);
        assertThat(execs.get(1).getStatus()).isEqualTo(StepExecutionStatus.COMPLETED);

        // Both attempts used the same idempotency key for step 0
        assertThat(execs.get(0).getAttemptNumber()).isEqualTo(1); // same attempt, not incremented
    }

    // ── Test 2: TREAT_AS_FAILURE ──────────────────────────────────────────────

    @Test
    void timeout_treatAsFailure_triggersCompensationImmediately() throws Exception {
        String ctxJson = objectMapper.writeValueAsString(new TestCtx());
        UUID sagaId = orchestrator.startSaga(TREAT_AS_FAILURE_TYPE, ctxJson, List.of());

        // Step 1 timed out with TREAT_AS_FAILURE → immediately marked FAILED,
        // compensation triggers for step 0 → saga COMPENSATED
        SagaInstance saga = sagaRepo.findById(sagaId).orElseThrow();
        assertThat(saga.getStatus()).isEqualTo(SagaStatus.COMPENSATED);

        // Step 0's compensation ran
        assertThat(treatAsFailureCompCount.get()).isEqualTo(1);

        // Step 1's step_execution should be FAILED (not TIMED_OUT or IN_PROGRESS)
        List<SagaStepExecution> execs = stepRepo.findBySagaIdOrderByStepIndexAscAttemptNumberAsc(sagaId);
        SagaStepExecution step1Exec = execs.stream()
                .filter(e -> e.getStepIndex() == 1).findFirst().orElseThrow();
        assertThat(step1Exec.getStatus()).isEqualTo(StepExecutionStatus.FAILED);

        // Compensation record for step 0 exists and is COMPLETED
        List<CompensationStep> comps = compStepRepo.findBySagaIdOrderByStepIndexAsc(sagaId);
        assertThat(comps).hasSize(1);
        assertThat(comps.get(0).getStepIndex()).isEqualTo(0);
        assertThat(comps.get(0).getStatus()).isEqualTo(CompensationStatus.COMPLETED);

        // No timeout scanner needed — TREAT_AS_FAILURE is handled inline
        assertThat(stepRepo.findTimedOutSteps(Instant.now())).isEmpty();
    }

    // ── Test 3: WAIT_FOR_CALLBACK — callback arrives, saga resumes ────────────

    @Test
    void timeout_waitForCallback_externalCallbackResumes_sagaCompletes() throws Exception {
        String ctxJson = objectMapper.writeValueAsString(new TestCtx());
        UUID sagaId = orchestrator.startSaga(WAIT_FOR_CALLBACK_TYPE, ctxJson, List.of());

        // Step 1 timed out with WAIT_FOR_CALLBACK → saga is parked
        SagaInstance saga = sagaRepo.findById(sagaId).orElseThrow();
        assertThat(saga.getStatus()).isEqualTo(SagaStatus.WAITING_FOR_CALLBACK);
        assertThat(saga.getCurrentStep()).isEqualTo(1); // still at step 1 (not advanced)

        // Step 1's step_execution is TIMED_OUT
        List<SagaStepExecution> execs = stepRepo.findBySagaIdOrderByStepIndexAscAttemptNumberAsc(sagaId);
        SagaStepExecution step1 = execs.stream().filter(e -> e.getStepIndex() == 1).findFirst().orElseThrow();
        assertThat(step1.getStatus()).isEqualTo(StepExecutionStatus.TIMED_OUT);

        // TimeoutScanner should NOT pick this up (step is TIMED_OUT, not IN_PROGRESS)
        timeoutScanService.scan();
        assertThat(sagaRepo.findById(sagaId).orElseThrow().getStatus())
                .isEqualTo(SagaStatus.WAITING_FOR_CALLBACK); // still parked

        // External callback arrives with success
        orchestrator.receiveCallback(sagaId, 1, true, "debit-completed");

        // Saga resumed, step 2 executed, saga completed
        saga = sagaRepo.findById(sagaId).orElseThrow();
        assertThat(saga.getStatus()).isEqualTo(SagaStatus.COMPLETED);
        assertThat(waitForCallbackStep1RunCount.get()).isEqualTo(1); // step 2 ran once

        // Step 1 now COMPLETED (updated from TIMED_OUT)
        execs = stepRepo.findBySagaIdOrderByStepIndexAscAttemptNumberAsc(sagaId);
        step1 = execs.stream().filter(e -> e.getStepIndex() == 1).findFirst().orElseThrow();
        assertThat(step1.getStatus()).isEqualTo(StepExecutionStatus.COMPLETED);
    }

    // ── Test 4: Crash recovery — orphaned saga scanner resumes from correct step

    @Test
    void orphanedSaga_scannerClaimsAndResumes_sagaCompletes() throws Exception {
        // Arrange: manually create a saga that is "stuck" at step 2 (steps 0 and 1 done),
        // as if the original node crashed after completing steps 0 and 1.
        SagaDefinition<?> def = registry.lookup(CRASH_RECOVERY_TYPE);
        String ctxJson = objectMapper.writeValueAsString(new TestCtx());

        // Create saga at currentStep=2 with EXECUTING status, owned by "dead-node"
        SagaInstance saga = sagaRepo.save(SagaInstance.create(CRASH_RECOVERY_TYPE, ctxJson));
        saga.setStatus(SagaStatus.EXECUTING);
        saga.setCurrentStep(2); // steps 0 and 1 already done
        saga.setLockOwner("dead-node-xyz");
        saga.setHeartbeatAt(Instant.now().minusSeconds(120));
        saga.setLeaseExpiresAt(Instant.now().minusSeconds(60)); // expired!
        saga = sagaRepo.save(saga);
        UUID sagaId = saga.getId();

        // Act: orphaned saga scanner runs and claims this saga
        orphanedSagaScanService.scan();

        // Verify: saga completed (scanner resumed execution from step 2)
        saga = sagaRepo.findById(sagaId).orElseThrow();
        assertThat(saga.getStatus()).isEqualTo(SagaStatus.COMPLETED);

        // Verify: step 2 executed exactly once
        assertThat(crashRecoveryStep2RunCount.get()).isEqualTo(1);

        // Verify: one step_execution record for step 2 (COMPLETED)
        List<SagaStepExecution> execs = stepRepo.findBySagaIdOrderByStepIndexAscAttemptNumberAsc(sagaId);
        assertThat(execs).hasSize(1); // only step 2 was executed (steps 0 and 1 already done before crash)
        assertThat(execs.get(0).getStepIndex()).isEqualTo(2);
        assertThat(execs.get(0).getStatus()).isEqualTo(StepExecutionStatus.COMPLETED);

        // Verify: the correct idempotency key was used: SHA-256(sagaId + ":fwd:2:1")
        com.saga.orchestrator.infrastructure.IdempotencyKeyGenerator kg =
                new com.saga.orchestrator.infrastructure.IdempotencyKeyGenerator();
        String expectedKey = kg.forwardKey(sagaId, 2, 1);
        assertThat(execs.get(0).getIdempotencyKey()).isEqualTo(expectedKey);
    }
}
