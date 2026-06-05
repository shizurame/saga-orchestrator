package com.saga.orchestrator.engine;

import com.saga.orchestrator.definition.*;
import com.saga.orchestrator.persistence.entity.CompensationStep;
import com.saga.orchestrator.persistence.entity.SagaInstance;
import com.saga.orchestrator.persistence.repository.SagaInstanceRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

/**
 * Primary entry point for all saga lifecycle operations.
 *
 * startSaga    — validates definition, initializes atomically, drives forward execution,
 *                then compensation if the forward engine stopped due to failure
 * resumeSaga   — called by the crash-recovery scanner to resume an orphaned saga
 * receiveCallback — handles external callbacks for WAIT_FOR_CALLBACK steps (Phase 6)
 */
@Service
public class SagaOrchestrator {

    private final SagaDefinitionRegistry registry;
    private final SagaInitializer        initializer;
    private final ForwardExecutionEngine forwardEngine;
    private final CompensationEngine     compensationEngine;
    private final CompensationPlanWriter planWriter;
    private final StepPersistenceService persistence;
    private final SagaInstanceRepository sagaRepo;

    public SagaOrchestrator(SagaDefinitionRegistry registry,
                             SagaInitializer initializer,
                             ForwardExecutionEngine forwardEngine,
                             CompensationEngine compensationEngine,
                             CompensationPlanWriter planWriter,
                             StepPersistenceService persistence,
                             SagaInstanceRepository sagaRepo) {
        this.registry           = registry;
        this.initializer        = initializer;
        this.forwardEngine      = forwardEngine;
        this.compensationEngine = compensationEngine;
        this.planWriter         = planWriter;
        this.persistence        = persistence;
        this.sagaRepo           = sagaRepo;
    }

    /**
     * Start a new saga.
     *
     * @param sagaType       registered saga type name
     * @param contextJson    JSON-serialized initial context
     * @param lockedEntities business entities to lock for the saga's duration
     * @return the new saga's ID
     * @throws com.saga.orchestrator.definition.EntityLockConflictException if any entity is locked
     */
    public UUID startSaga(String sagaType, String contextJson, List<EntityRef> lockedEntities) {
        SagaDefinition<?> definition = registry.lookup(sagaType);

        // SagaInitializer.initialize is @Transactional and called via Spring proxy (separate bean).
        // The TX commits before executeFromStep runs — write-ordering is preserved.
        UUID sagaId = initializer.initialize(sagaType, contextJson, lockedEntities);

        // Execute steps — each step has its own REQUIRES_NEW TX (via StepPersistenceService)
        SagaInstance saga = sagaRepo.findById(sagaId).orElseThrow();
        forwardEngine.executeFromStep(saga, definition, 0);

        // If a step failed with prior compensatables, the engine transitioned to COMPENSATING.
        // Reload the saga so we see the committed status before deciding whether to compensate.
        saga = sagaRepo.findById(sagaId).orElseThrow();
        if (saga.getStatus() == SagaStatus.COMPENSATING) {
            compensationEngine.compensate(saga, definition);
        }

        return sagaId;
    }

    /**
     * Resume an orphaned saga after node failover.
     * Called by the OrphanedSagaScanner after a successful claimSaga.
     */
    public void resumeSaga(UUID sagaId) {
        SagaInstance saga = sagaRepo.findById(sagaId)
                .orElseThrow(() -> new IllegalArgumentException("Saga not found: " + sagaId));

        if (saga.getStatus().isTerminal()) return;

        SagaDefinition<?> definition = registry.lookup(saga.getSagaType());

        switch (saga.getStatus()) {
            case EXECUTING -> {
                forwardEngine.executeFromStep(saga, definition, saga.getCurrentStep());
                saga = sagaRepo.findById(sagaId).orElseThrow();
                if (saga.getStatus() == SagaStatus.COMPENSATING) {
                    compensationEngine.compensate(saga, definition);
                }
            }
            case COMPENSATING -> compensationEngine.compensate(saga, definition);
            case WAITING_FOR_CALLBACK -> { /* no-op: awaiting external callback */ }
            default -> throw new IllegalStateException("Cannot resume saga in status: " + saga.getStatus());
        }
    }

    /**
     * Handle an external callback for a WAIT_FOR_CALLBACK step.
     *
     * On success: marks the timed-out step COMPLETED, transitions saga to EXECUTING,
     *             and continues forward execution from the next step.
     * On failure: marks the step FAILED and triggers compensation if prior COMPENSATABLE
     *             steps exist, otherwise transitions to FAILED.
     */
    public void receiveCallback(UUID sagaId, int stepIndex, boolean success, Object result) {
        SagaInstance saga = sagaRepo.findById(sagaId).orElseThrow();
        SagaDefinition<?> definition = registry.lookup(saga.getSagaType());
        SagaStep step = definition.stepAt(stepIndex);
        boolean isLastStep = (stepIndex == definition.stepCount() - 1);

        if (success) {
            persistence.persistCallbackSuccess(sagaId, stepIndex, isLastStep, step.name());

            if (!isLastStep) {
                SagaInstance refreshed = sagaRepo.findById(sagaId).orElseThrow();
                forwardEngine.executeFromStep(refreshed, definition, refreshed.getCurrentStep());
                refreshed = sagaRepo.findById(sagaId).orElseThrow();
                if (refreshed.getStatus() == SagaStatus.COMPENSATING) {
                    compensationEngine.compensate(refreshed, definition);
                }
            }
        } else {
            // Mark step FAILED (write-ordering: committed before saga status changes)
            persistence.persistCallbackFailure(sagaId, stepIndex);

            // Check prior compensatable steps and trigger compensation or fail
            boolean hasCompensatables = IntStream.range(0, stepIndex)
                    .anyMatch(i -> definition.stepAt(i).type() == StepType.COMPENSATABLE);
            if (hasCompensatables) {
                List<CompensationStep> plan = planWriter.buildPlan(sagaId, definition, stepIndex);
                persistence.transitionToCompensating(sagaId, plan, step.name());
                SagaInstance refreshed = sagaRepo.findById(sagaId).orElseThrow();
                compensationEngine.compensate(refreshed, definition);
            } else {
                persistence.transitionSaga(sagaId, SagaEvent.NO_COMPENSATABLES, step.name());
            }
        }
    }
}
