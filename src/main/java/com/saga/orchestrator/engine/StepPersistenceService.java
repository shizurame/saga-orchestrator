package com.saga.orchestrator.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.saga.orchestrator.definition.SagaEvent;
import com.saga.orchestrator.definition.SagaStatus;
import com.saga.orchestrator.definition.StepExecutionStatus;
import com.saga.orchestrator.event.SagaEventPublisher;
import com.saga.orchestrator.event.SagaExecutionEvent;
import com.saga.orchestrator.infrastructure.EntityLockManager;
import com.saga.orchestrator.infrastructure.HeartbeatService;
import com.saga.orchestrator.persistence.entity.CompensationStep;
import com.saga.orchestrator.persistence.entity.SagaInstance;
import com.saga.orchestrator.persistence.entity.SagaStepExecution;
import com.saga.orchestrator.persistence.repository.CompensationStepRepository;
import com.saga.orchestrator.persistence.repository.SagaInstanceRepository;
import com.saga.orchestrator.persistence.repository.SagaStepExecutionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Write-ordering persistence operations for both forward step execution and compensation.
 *
 * Every public method uses REQUIRES_NEW so it commits immediately, independently
 * of any outer transaction. This is the WAL semantics at the business-transaction
 * layer: intent is durable before the action runs; result is durable before the
 * engine advances to the next step.
 *
 * REQUIRES_NEW is required here — NOT as a workaround but as the correct design:
 * the intent must be visible to crash-recovery before the action executes, which
 * means it must commit even if a caller had an outer transaction open.
 *
 * Separation from ForwardExecutionEngine and CompensationEngine avoids the Spring
 * proxy self-invocation problem: @Transactional only applies when a method is called
 * through the proxy, so these operations live in their own @Service bean.
 */
@Service
class StepPersistenceService {

    private final SagaInstanceRepository sagaRepo;
    private final SagaStepExecutionRepository stepRepo;
    private final CompensationStepRepository compensationStepRepo;
    private final EntityLockManager entityLockManager;
    private final HeartbeatService heartbeatService;
    private final SagaStateMachine stateMachine;
    private final SagaEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;

    StepPersistenceService(SagaInstanceRepository sagaRepo,
                           SagaStepExecutionRepository stepRepo,
                           CompensationStepRepository compensationStepRepo,
                           EntityLockManager entityLockManager,
                           HeartbeatService heartbeatService,
                           SagaStateMachine stateMachine,
                           SagaEventPublisher eventPublisher,
                           ObjectMapper objectMapper) {
        this.sagaRepo             = sagaRepo;
        this.stepRepo             = stepRepo;
        this.compensationStepRepo = compensationStepRepo;
        this.entityLockManager    = entityLockManager;
        this.heartbeatService     = heartbeatService;
        this.stateMachine         = stateMachine;
        this.eventPublisher       = eventPublisher;
        this.objectMapper         = objectMapper;
    }

    // ── Forward step execution ────────────────────────────────────────────────

    /**
     * Write-ordering step 1: persist the intent record before executing the action.
     * The unique constraint uq_step_attempt on (sagaId, stepIndex, attemptNumber) acts
     * as the optimistic-concurrency guard — a concurrent executor racing on the same
     * step will get a DataIntegrityViolationException here, not after the action runs.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public SagaStepExecution persistIntent(UUID sagaId, int stepIndex, int attempt,
                                           String idempotencyKey, Instant timeoutAt) {
        SagaStepExecution exec = SagaStepExecution.createInProgress(
                sagaId, stepIndex, attempt, idempotencyKey, timeoutAt);
        return stepRepo.save(exec);
    }

    /**
     * Write-ordering step 2 (success path): persist the result and advance the saga.
     * Reloads the saga within this TX so @Version provides OCC against concurrent updates.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public SagaInstance persistStepSuccess(UUID sagaId, UUID executionId,
                                           Object result, String contextJson,
                                           boolean isLastStep, String stepName) {
        SagaStepExecution exec = stepRepo.findById(executionId).orElseThrow();
        exec.markCompleted(toJson(result));
        stepRepo.save(exec);

        SagaInstance saga = sagaRepo.findById(sagaId).orElseThrow();
        SagaStatus fromStatus = saga.getStatus();
        long started = exec.getStartedAt().toEpochMilli();

        saga.setContext(contextJson);
        saga.setCurrentStep(saga.getCurrentStep() + 1);

        SagaEvent sagaEvent   = isLastStep ? SagaEvent.SAGA_COMPLETED : SagaEvent.STEP_SUCCEEDED;
        SagaStatus nextStatus = stateMachine.transition(fromStatus, sagaEvent);
        saga.setStatus(nextStatus);

        if (nextStatus.isTerminal()) {
            heartbeatService.untrack(sagaId);
            entityLockManager.releaseAllLocksForSaga(sagaId);
        }

        SagaInstance saved = sagaRepo.save(saga);

        eventPublisher.publish(new SagaExecutionEvent(
                sagaId, saga.getSagaType(), stepName,
                fromStatus, nextStatus, Instant.now(),
                Instant.now().toEpochMilli() - started));

        return saved;
    }

    /**
     * Write-ordering step 2 (failure path): marks the step FAILED.
     * Saga status remains EXECUTING — the engine decides whether to retry or compensate.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void persistStepFailure(UUID sagaId, UUID executionId, String error) {
        SagaStepExecution exec = stepRepo.findById(executionId).orElseThrow();
        exec.markFailed(toJson(error));
        stepRepo.save(exec);
    }

    /** Marks step TIMED_OUT; timeout-mode routing is done by the caller. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void persistStepTimeout(UUID sagaId, UUID executionId) {
        SagaStepExecution exec = stepRepo.findById(executionId).orElseThrow();
        exec.markTimedOut();
        stepRepo.save(exec);
    }

    /**
     * Apply a saga-level event: reloads the saga, runs the state machine, saves.
     * Called when retries are exhausted with NO_COMPENSATABLES or when parking
     * for a callback (STEP_TIMED_OUT).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void transitionSaga(UUID sagaId, SagaEvent event, String stepName) {
        SagaInstance saga = sagaRepo.findById(sagaId).orElseThrow();
        SagaStatus from = saga.getStatus();
        SagaStatus to   = stateMachine.transition(from, event);
        saga.setStatus(to);
        sagaRepo.save(saga);

        if (to.isTerminal()) {
            heartbeatService.untrack(sagaId);
            entityLockManager.releaseAllLocksForSaga(sagaId);
        }

        eventPublisher.publish(new SagaExecutionEvent(
                sagaId, saga.getSagaType(), stepName, from, to, Instant.now(), 0L));
    }

    // ── Compensation ─────────────────────────────────────────────────────────

    /**
     * Write all compensation plan records AND transition the saga to COMPENSATING
     * in a single atomic transaction (REQUIRES_NEW).
     *
     * The plan must be written before the status change is committed so that crash
     * recovery can load PENDING compensation steps and resume from the correct point.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void transitionToCompensating(UUID sagaId, List<CompensationStep> plan, String stepName) {
        compensationStepRepo.saveAll(plan);

        SagaInstance saga = sagaRepo.findById(sagaId).orElseThrow();
        SagaStatus from = saga.getStatus();
        SagaStatus to   = stateMachine.transition(from, SagaEvent.STEP_FAILED);
        saga.setStatus(to);
        sagaRepo.save(saga);

        eventPublisher.publish(new SagaExecutionEvent(
                sagaId, saga.getSagaType(), stepName, from, to, Instant.now(), 0L));
    }

    /**
     * Compensation write-ordering step 1: mark the compensation step IN_PROGRESS.
     * Increments attemptNumber so each attempt is observable.
     * The idempotency key is NOT changed — compensation always uses the same key
     * so participants can return a cached result on retry.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void persistCompensationIntent(UUID compStepId) {
        CompensationStep cs = compensationStepRepo.findById(compStepId).orElseThrow();
        cs.markInProgress();
        compensationStepRepo.save(cs);
    }

    /**
     * Compensation write-ordering step 2 (success path): mark COMPLETED with result.
     * Publishes a COMPENSATING→COMPENSATING event so metrics listeners can track per-step
     * compensation counts without needing a separate event type.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void persistCompensationSuccess(UUID compStepId, String resultJson) {
        CompensationStep cs = compensationStepRepo.findById(compStepId).orElseThrow();
        cs.markCompleted(resultJson);
        compensationStepRepo.save(cs);

        SagaInstance saga = sagaRepo.findById(cs.getSagaId()).orElseThrow();
        eventPublisher.publish(new SagaExecutionEvent(
                saga.getId(), saga.getSagaType(), cs.getAction(),
                SagaStatus.COMPENSATING, SagaStatus.COMPENSATING, Instant.now(), 0L));
    }

    /**
     * Compensation write-ordering step 2 (failure path): mark FAILED.
     * The engine will retry up to maxAttempts before giving up.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void persistCompensationFailure(UUID compStepId) {
        CompensationStep cs = compensationStepRepo.findById(compStepId).orElseThrow();
        cs.markFailed();
        compensationStepRepo.save(cs);
    }

    /**
     * Terminal compensation transition: release all entity locks AND update saga status
     * in a single atomic transaction.
     *
     * Lock release is included here — never in a separate TX — so it is impossible for
     * a saga to end in a terminal state with orphaned entity locks.
     *
     * Invoked with:
     *   ALL_COMPENSATED        → COMPENSATING → COMPENSATED
     *   COMPENSATION_EXHAUSTED → COMPENSATING → DEAD_LETTER
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void releaseLocksAndTransition(UUID sagaId, SagaEvent event, String stepName) {
        // Lock release joins this REQUIRES_NEW TX (EntityLockManager uses default REQUIRED propagation)
        entityLockManager.releaseAllLocksForSaga(sagaId);

        SagaInstance saga = sagaRepo.findById(sagaId).orElseThrow();
        SagaStatus from = saga.getStatus();
        SagaStatus to   = stateMachine.transition(from, event);
        saga.setStatus(to);
        sagaRepo.save(saga);

        heartbeatService.untrack(sagaId);

        eventPublisher.publish(new SagaExecutionEvent(
                sagaId, saga.getSagaType(), stepName, from, to, Instant.now(), 0L));
    }

    // ── Timeout and Callback (Phase 6) ────────────────────────────────────────

    /**
     * Extend the timeout deadline for an IN_PROGRESS step that timed out but whose
     * retry also timed out. Prevents the scanner from re-firing every cycle.
     * timeout_at is updatable=false in the entity, so a native query is required.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void extendStepTimeout(UUID executionId, Instant newTimeoutAt) {
        stepRepo.extendTimeout(executionId, Timestamp.from(newTimeoutAt));
    }

    /**
     * Callback success: find the TIMED_OUT step execution, mark COMPLETED, and
     * transition the saga from WAITING_FOR_CALLBACK to EXECUTING (or COMPLETED if last step).
     *
     * isLastStep=true  → fires SAGA_COMPLETED   → WAITING_FOR_CALLBACK → COMPLETED
     * isLastStep=false → fires CALLBACK_SUCCESS → WAITING_FOR_CALLBACK → EXECUTING
     *
     * Caller (SagaOrchestrator.receiveCallback) continues forward execution if not last step.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void persistCallbackSuccess(UUID sagaId, int stepIndex, boolean isLastStep, String stepName) {
        SagaStepExecution exec = stepRepo
                .findBySagaIdAndStepIndexAndStatus(sagaId, stepIndex, StepExecutionStatus.TIMED_OUT)
                .orElseThrow(() -> new IllegalStateException(
                        "No TIMED_OUT execution for saga=" + sagaId + " step=" + stepIndex));
        exec.markCompleted(null);
        stepRepo.save(exec);

        SagaInstance saga = sagaRepo.findById(sagaId).orElseThrow();
        SagaStatus from = saga.getStatus(); // WAITING_FOR_CALLBACK
        SagaEvent event = isLastStep ? SagaEvent.SAGA_COMPLETED : SagaEvent.CALLBACK_SUCCESS;
        SagaStatus to   = stateMachine.transition(from, event);
        saga.setStatus(to);
        saga.setCurrentStep(saga.getCurrentStep() + 1); // advance past the callback step
        sagaRepo.save(saga);

        if (to.isTerminal()) {
            heartbeatService.untrack(sagaId);
        }

        eventPublisher.publish(new SagaExecutionEvent(
                sagaId, saga.getSagaType(), stepName, from, to, Instant.now(), 0L));
    }

    /**
     * Callback failure: find the TIMED_OUT step execution and mark FAILED.
     * The saga status is NOT changed here — caller must call transitionToCompensating
     * or transitionSaga(NO_COMPENSATABLES) in a separate REQUIRES_NEW TX.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void persistCallbackFailure(UUID sagaId, int stepIndex) {
        SagaStepExecution exec = stepRepo
                .findBySagaIdAndStepIndexAndStatus(sagaId, stepIndex, StepExecutionStatus.TIMED_OUT)
                .orElseThrow(() -> new IllegalStateException(
                        "No TIMED_OUT execution for saga=" + sagaId + " step=" + stepIndex));
        exec.markFailed(toJson("callback indicated failure"));
        stepRepo.save(exec);
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    private String toJson(Object value) {
        if (value == null) return null;
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "\"" + String.valueOf(value).replace("\"", "\\\"") + "\"";
        }
    }
}
