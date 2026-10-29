package com.saga.orchestrator.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.saga.orchestrator.definition.*;
import com.saga.orchestrator.infrastructure.HeartbeatService;
import com.saga.orchestrator.persistence.entity.CompensationStep;
import com.saga.orchestrator.persistence.entity.SagaInstance;
import com.saga.orchestrator.persistence.repository.CompensationStepRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * Executes all pending compensation steps for a saga in COMPENSATING status.
 *
 * Write-ordering protocol (mirrors forward execution):
 *   1. persistCompensationIntent  — marks IN_PROGRESS before calling the compensation action
 *   2. Execute compensation action — outside any TX (no Spring TX wrapper)
 *   3. persistCompensationSuccess/Failure — commits result before advancing
 *
 * Crash safety: on recovery, findBySagaIdAndStatusIn(PENDING, IN_PROGRESS) is used.
 * An IN_PROGRESS step means the process crashed between intent and result — it is
 * re-executed with the same idempotency key, and the participant returns the cached result.
 *
 * Retry semantics for compensation: same idempotency key across all attempts (attempt=0).
 * The participant must be idempotent on that key. Compensation retries always use attempt=0
 * so the participant can cache and replay the result safely.
 *
 * Terminal transitions:
 *   All compensations succeed → ALL_COMPENSATED → COMPENSATED + entity locks released
 *   Any exhausted retries     → COMPENSATION_EXHAUSTED → DEAD_LETTER + entity locks released
 */
@Component
public class CompensationEngine {

    private static final Logger log = LoggerFactory.getLogger(CompensationEngine.class);

    private final StepPersistenceService persistence;
    private final CompensationStepRepository compensationStepRepo;
    private final ObjectMapper objectMapper;
    private final HeartbeatService heartbeatService;

    CompensationEngine(StepPersistenceService persistence,
                       CompensationStepRepository compensationStepRepo,
                       ObjectMapper objectMapper,
                       HeartbeatService heartbeatService) {
        this.persistence          = persistence;
        this.compensationStepRepo = compensationStepRepo;
        this.objectMapper         = objectMapper;
        this.heartbeatService     = heartbeatService;
    }

    /**
     * Execute all remaining (PENDING and IN_PROGRESS) compensation steps for this saga.
     *
     * Idempotent: if the saga is already terminal (e.g. COMPENSATED from a prior run),
     * this is a no-op. If there are no PENDING/IN_PROGRESS steps left, the saga is
     * transitioned to COMPENSATED (handles crash-after-last-comp-but-before-status-update).
     */
    @SuppressWarnings("unchecked")
    public void compensate(SagaInstance saga, SagaDefinition<?> definition) {
        UUID sagaId = saga.getId();

        if (saga.getStatus().isTerminal()) return;

        SagaContext context = deserializeContext(saga, definition);

        try {
            compensateRemaining(sagaId, definition, context);
        } catch (FencedOutException e) {
            // Another node has claimed this saga since we started — stop immediately.
            // Whatever write triggered this was rolled back in its own REQUIRES_NEW TX;
            // the new owner is the sole writer from here on.
            log.warn("{} — stopping compensation for this node", e.getMessage());
            heartbeatService.untrack(sagaId);
        }
    }

    private void compensateRemaining(UUID sagaId, SagaDefinition<?> definition, SagaContext context) {
        // Load PENDING, IN_PROGRESS, and FAILED — handles fresh starts and both shapes of
        // crash recovery. FAILED must be included: a crash right after persistCompensationFailure
        // commits (before the next retry, or before the DEAD_LETTER transition if that failure
        // was the last allowed attempt) used to leave the row invisible to this query — silently
        // skipped forever, with the saga wrongly reaching COMPENSATED despite this step's
        // compensation never actually completing. Repository returns rows in DESC step_index
        // order (reverse execution order), so an already-exhausted row is always checked before
        // any step compensated earlier in forward order.
        List<CompensationStep> remaining = compensationStepRepo
                .findBySagaIdAndStatusInOrderByStepIndexDesc(
                        sagaId,
                        List.of(CompensationStatus.PENDING, CompensationStatus.IN_PROGRESS,
                                CompensationStatus.FAILED));

        for (CompensationStep compStep : remaining) {
            SagaStep stepDef  = definition.stepAt(compStep.getStepIndex());
            int maxAttempts   = stepDef.retryPolicy().maxAttempts();

            if (compStep.getStatus() == CompensationStatus.FAILED
                    && compStep.getAttemptNumber() >= maxAttempts) {
                // Already exhausted before this resume — the DEAD_LETTER decision itself
                // never committed. Make it now instead of silently treating this as done.
                log.error("Compensation for '{}' saga={} was already exhausted before this resume " +
                        "(crash before DEAD_LETTER committed) → DEAD_LETTER", stepDef.name(), sagaId);
                persistence.releaseLocksAndTransition(sagaId, SagaEvent.COMPENSATION_EXHAUSTED, stepDef.name());
                return;
            }

            boolean succeeded = false;
            // Continue from the next attempt — not always 1 — so a crash between retries
            // doesn't grant extra attempts beyond maxAttempts, and so a FAILED-but-not-
            // exhausted row (attemptNumber < maxAttempts) actually gets retried here instead
            // of restarting from scratch.
            int startAttempt = compStep.getAttemptNumber() + 1;

            for (int attempt = startAttempt; attempt <= maxAttempts; attempt++) {
                // Step 1: persist intent — marks IN_PROGRESS and increments attemptNumber.
                // The idempotency key is always compensationKey(sagaId, stepIndex, 0) —
                // same across all retries so the participant can return a cached result.
                persistence.persistCompensationIntent(compStep.getId());

                // Step 2: execute the compensation action (outside any TX)
                ExecutionResult result = executeCompensation(stepDef, context, compStep.getIdempotencyKey());

                // Step 3: persist result
                if (result instanceof ExecutionResult.Success success) {
                    persistence.persistCompensationSuccess(compStep.getId(), toJson(success.result()));
                    succeeded = true;
                    break;
                }

                String error = result instanceof ExecutionResult.Failure f ? f.error() : "timed out";
                log.warn("Compensation '{}' saga={} attempt={}/{} failed: {}",
                        stepDef.name(), sagaId, attempt, maxAttempts, error);
                persistence.persistCompensationFailure(compStep.getId());

                if (attempt == maxAttempts) {
                    log.error("Compensation exhausted for '{}' saga={} → DEAD_LETTER",
                            stepDef.name(), sagaId);
                    persistence.releaseLocksAndTransition(sagaId, SagaEvent.COMPENSATION_EXHAUSTED, stepDef.name());
                    return;
                }
                sleepIfNeeded(stepDef.retryPolicy().delayForAttempt(attempt + 1));
            }

            if (!succeeded) {
                // Defensive; the loop above always returns on exhaustion
                persistence.releaseLocksAndTransition(sagaId, SagaEvent.COMPENSATION_EXHAUSTED, stepDef.name());
                return;
            }
        }

        // All pending compensations complete — or there were none (full crash-recovery case).
        // Either way the saga is fully compensated.
        String finalStep = remaining.isEmpty()
                ? "compensation-complete"
                : definition.stepAt(remaining.get(remaining.size() - 1).getStepIndex()).name();
        persistence.releaseLocksAndTransition(sagaId, SagaEvent.ALL_COMPENSATED, finalStep);
    }

    @SuppressWarnings("unchecked")
    private ExecutionResult executeCompensation(SagaStep stepDef, SagaContext context, String key) {
        try {
            return stepDef.compensation().execute(context, key);
        } catch (Exception e) {
            return new ExecutionResult.Failure(e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private SagaContext deserializeContext(SagaInstance saga, SagaDefinition<?> definition) {
        try {
            return (SagaContext) objectMapper.readValue(saga.getContext(), definition.contextClass());
        } catch (Exception e) {
            throw new RuntimeException("Failed to deserialize context for saga " + saga.getId(), e);
        }
    }

    private String toJson(Object value) {
        if (value == null) return null;
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "\"" + String.valueOf(value).replace("\"", "\\\"") + "\"";
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
