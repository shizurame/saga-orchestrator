package com.saga.orchestrator.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.saga.orchestrator.definition.*;
import com.saga.orchestrator.infrastructure.HeartbeatService;
import com.saga.orchestrator.infrastructure.IdempotencyKeyGenerator;
import com.saga.orchestrator.persistence.entity.CompensationStep;
import com.saga.orchestrator.persistence.entity.SagaInstance;
import com.saga.orchestrator.persistence.entity.SagaStepExecution;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;

/**
 * Drives forward step execution with the write-ordering (WAL) protocol:
 *
 *   1. persistIntent  — commit IN_PROGRESS record before executing (crash-safe)
 *   2. execute action — runs the step logic (bean or HTTP, outside any TX)
 *   3. persistResult  — commit COMPLETED/FAILED/TIMED_OUT and advance saga step
 *
 * If the orchestrator crashes between steps 1 and 3, the recovery scanner finds the
 * IN_PROGRESS record and re-executes the step using the same idempotency key, ensuring
 * the participant's response is idempotent (returns cached result on duplicate key).
 *
 * Retry semantics:
 *   Failure retry  → attempt_number++ → new idempotency key → fresh attempt at participant
 *   Timeout retry  → same attempt_number → same key → participant returns cached result
 *   (Timeout retry is handled by Phase 6's TimeoutHandler, not in this loop)
 *
 * When retries are exhausted:
 *   Has prior COMPENSATABLE steps → builds compensation plan + transitions to COMPENSATING
 *   No prior COMPENSATABLE steps  → transitions to FAILED (NO_COMPENSATABLES event)
 *
 * SagaOrchestrator detects COMPENSATING status after this engine returns and
 * dispatches to CompensationEngine.
 */
@Component
public class ForwardExecutionEngine {

    private static final Logger log = LoggerFactory.getLogger(ForwardExecutionEngine.class);

    private final StepPersistenceService persistence;
    private final StepExecutorFactory    executorFactory;
    private final IdempotencyKeyGenerator keyGenerator;
    private final CompensationPlanWriter planWriter;
    private final ObjectMapper           objectMapper;
    private final HeartbeatService       heartbeatService;

    public ForwardExecutionEngine(StepPersistenceService persistence,
                                  StepExecutorFactory executorFactory,
                                  IdempotencyKeyGenerator keyGenerator,
                                  CompensationPlanWriter planWriter,
                                  ObjectMapper objectMapper,
                                  HeartbeatService heartbeatService) {
        this.persistence    = persistence;
        this.executorFactory = executorFactory;
        this.keyGenerator   = keyGenerator;
        this.planWriter     = planWriter;
        this.objectMapper   = objectMapper;
        this.heartbeatService = heartbeatService;
    }

    /**
     * Execute the saga starting from {@code startStepIndex}.
     * Called by SagaOrchestrator.startSaga (index 0) and resumeSaga (index = currentStep).
     */
    @SuppressWarnings("unchecked")
    public void executeFromStep(SagaInstance saga, SagaDefinition<?> definition, int startStepIndex) {
        UUID sagaId       = saga.getId();
        SagaContext context = deserializeContext(saga, definition);

        try {
            for (int i = startStepIndex; i < definition.stepCount(); i++) {
                SagaStep step = definition.stepAt(i);
                boolean last  = (i == definition.stepCount() - 1);

                boolean proceed = executeWithRetry(sagaId, step, i, last, definition, context);
                if (!proceed) return; // failure, timeout, or OCC loss — engine stops
            }
        } catch (FencedOutException e) {
            // Another node has claimed this saga since we started — stop immediately.
            // Whatever write triggered this was rolled back in its own REQUIRES_NEW TX;
            // the new owner is the sole writer from here on.
            log.warn("{} — stopping forward execution for this node", e.getMessage());
            heartbeatService.untrack(sagaId);
        }
    }

    /**
     * Execute one step with its RetryPolicy. Returns true if the step succeeded
     * and the engine should continue to the next step.
     */
    private boolean executeWithRetry(UUID sagaId, SagaStep step, int stepIndex,
                                     boolean isLastStep, SagaDefinition<?> definition,
                                     SagaContext context) {
        int maxAttempts = step.retryPolicy().maxAttempts();

        // Resume support: a crash can leave this step in one of two recoverable states —
        // restarting at a fresh attempt 1 in either case would collide with an already-
        // existing row via uq_step_attempt and silently stall (both shapes of the same bug):
        //   - an IN_PROGRESS row (crash between persistIntent and persistResult) — reuse it.
        //   - one or more FAILED rows, no IN_PROGRESS row (crash after a failure committed,
        //     before the next retry — or the compensate-or-fail decision after the LAST
        //     allowed attempt — ever committed). Continue from the next attempt number; if
        //     the last FAILED row was already the final allowed attempt, the compensate-or-
        //     fail decision itself never happened, so make it now instead of trying again.
        // Only the step actually being resumed can have any such row (every step after it has
        // never been attempted), so these lookups are a correctness check on every step entry,
        // not an optimization — cheap, and empty in the common case.
        Optional<SagaStepExecution> resuming = persistence.findInProgress(sagaId, stepIndex);
        int startAttempt;
        if (resuming.isPresent()) {
            startAttempt = resuming.get().getAttemptNumber();
        } else {
            Optional<SagaStepExecution> latest = persistence.findLatestAttempt(sagaId, stepIndex);
            if (latest.isPresent() && latest.get().getAttemptNumber() >= maxAttempts) {
                log.warn("Step '{}' saga={} already exhausted all {} attempts before this resume " +
                        "(crash before the compensate-or-fail decision committed) — deciding now",
                        step.name(), sagaId, maxAttempts);
                triggerCompensationOrFail(sagaId, definition, stepIndex, step.name());
                return false;
            }
            startAttempt = latest.map(e -> e.getAttemptNumber() + 1).orElse(1);
        }

        for (int attempt = startAttempt; attempt <= maxAttempts; attempt++) {
            String key;
            SagaStepExecution execution;

            if (resuming.isPresent() && attempt == startAttempt) {
                execution = resuming.get();
                key = execution.getIdempotencyKey();
                log.info("Resuming crashed step '{}' saga={} attempt={} with existing key",
                        step.name(), sagaId, attempt);
            } else {
                key = keyGenerator.forwardKey(sagaId, stepIndex, attempt);
                Instant timeoutAt = Instant.now().plus(step.timeout());
                // Step 1: persist intent — commits before action runs
                try {
                    execution = persistence.persistIntent(sagaId, stepIndex, attempt, key, timeoutAt);
                } catch (DataIntegrityViolationException e) {
                    // Another node already started this step (uq_step_attempt conflict) — OCC loss
                    log.warn("OCC: another executor already started saga={} step={} attempt={}",
                            sagaId, stepIndex, attempt);
                    return false;
                }
            }

            // Step 2: execute action (outside any TX)
            StepActionExecutor executor = executorFactory.select(step);
            ExecutionResult result = executor.execute(step, context, key);

            // Step 3: persist result
            if (result instanceof ExecutionResult.Success success) {
                String contextJson = serializeContext(context);
                persistence.persistStepSuccess(
                        sagaId, execution.getId(), success.result(),
                        contextJson, isLastStep, step.name());
                return true; // advance to next step

            } else if (result instanceof ExecutionResult.Failure failure) {
                persistence.persistStepFailure(sagaId, execution.getId(), failure.error());
                log.warn("Step '{}' saga={} attempt={}/{} failed: {}",
                        step.name(), sagaId, attempt, maxAttempts, failure.error());

                if (attempt < maxAttempts) {
                    Duration delay = step.retryPolicy().delayForAttempt(attempt + 1);
                    sleepIfNeeded(delay);
                    continue;
                }
                // Retries exhausted — write compensation plan (if needed) and stop
                triggerCompensationOrFail(sagaId, definition, stepIndex, step.name());
                return false;

            } else { // TimedOut
                switch (step.timeoutMode()) {
                    case RETRY_WITH_IDEMPOTENCY ->
                        // Keep IN_PROGRESS — TimeoutScanner re-executes with the same idempotency key.
                        // The participant returns a cached result if it already processed the request.
                        log.warn("Step '{}' saga={} timed out, left IN_PROGRESS for scanner retry", step.name(), sagaId);

                    case TREAT_AS_FAILURE -> {
                        // Treat the timeout as an outright failure — trigger compensation immediately.
                        persistence.persistStepFailure(sagaId, execution.getId(), "timed out (TREAT_AS_FAILURE)");
                        triggerCompensationOrFail(sagaId, definition, stepIndex, step.name());
                    }

                    case WAIT_FOR_CALLBACK -> {
                        // Mark timed out and park saga; external system will deliver the result via callback.
                        persistence.persistStepTimeout(sagaId, execution.getId());
                        persistence.transitionSaga(sagaId, SagaEvent.STEP_TIMED_OUT, step.name());
                    }
                }
                return false;
            }
        }
        return false; // unreachable — loop always returns inside
    }

    /**
     * Called when forward retries are exhausted. Checks whether any prior steps are
     * COMPENSATABLE and either writes the compensation plan (→ COMPENSATING) or
     * transitions directly to FAILED (NO_COMPENSATABLES). Both are written atomically
     * via REQUIRES_NEW transactions in StepPersistenceService.
     *
     * SagaOrchestrator picks up the COMPENSATING status and dispatches to CompensationEngine.
     */
    private void triggerCompensationOrFail(UUID sagaId, SagaDefinition<?> definition,
                                           int failedStepIndex, String stepName) {
        boolean hasCompensatables = IntStream.range(0, failedStepIndex)
                .anyMatch(i -> definition.stepAt(i).type() == StepType.COMPENSATABLE);

        if (hasCompensatables) {
            List<CompensationStep> plan = planWriter.buildPlan(sagaId, definition, failedStepIndex);
            persistence.transitionToCompensating(sagaId, plan, stepName);
        } else {
            persistence.transitionSaga(sagaId, SagaEvent.NO_COMPENSATABLES, stepName);
        }
    }

    @SuppressWarnings("unchecked")
    private SagaContext deserializeContext(SagaInstance saga, SagaDefinition<?> definition) {
        try {
            return (SagaContext) objectMapper.readValue(saga.getContext(), definition.contextClass());
        } catch (Exception e) {
            throw new RuntimeException("Failed to deserialize saga context for " + saga.getId(), e);
        }
    }

    private String serializeContext(SagaContext context) {
        try {
            return objectMapper.writeValueAsString(context);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize saga context", e);
        }
    }

    private void sleepIfNeeded(Duration delay) {
        if (delay.isZero() || delay.isNegative()) return;
        try {
            Thread.sleep(delay.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
