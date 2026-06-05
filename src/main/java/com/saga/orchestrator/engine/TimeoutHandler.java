package com.saga.orchestrator.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.saga.orchestrator.definition.*;
import com.saga.orchestrator.persistence.entity.CompensationStep;
import com.saga.orchestrator.persistence.entity.SagaInstance;
import com.saga.orchestrator.persistence.entity.SagaStepExecution;
import com.saga.orchestrator.persistence.repository.SagaInstanceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

/**
 * Handles RETRY_WITH_IDEMPOTENCY timeout retries for the TimeoutScanner.
 *
 * When a step's action timed out and the mode is RETRY_WITH_IDEMPOTENCY, the
 * step_execution record is left IN_PROGRESS in the DB (ForwardExecutionEngine does NOT
 * call persistStepTimeout for this mode). The TimeoutScanner finds these records and
 * delegates here.
 *
 * The key property: retry uses the SAME idempotency key (same attempt_number) so the
 * participant returns the cached result if it already processed the original request.
 *
 * On retry success: marks the IN_PROGRESS record COMPLETED and continues forward execution.
 * On retry failure: marks FAILED and triggers compensation (just like exhausted retries).
 * Still timing out: extends the timeout deadline so the scanner doesn't re-fire next cycle.
 */
@Component
public class TimeoutHandler {

    private static final Logger log = LoggerFactory.getLogger(TimeoutHandler.class);

    private final StepPersistenceService   persistence;
    private final StepExecutorFactory      executorFactory;
    private final SagaDefinitionRegistry   registry;
    private final SagaInstanceRepository   sagaRepo;
    private final CompensationPlanWriter   planWriter;
    private final CompensationEngine       compensationEngine;
    private final ForwardExecutionEngine   forwardEngine;
    private final ObjectMapper             objectMapper;

    public TimeoutHandler(StepPersistenceService persistence,
                          StepExecutorFactory executorFactory,
                          SagaDefinitionRegistry registry,
                          SagaInstanceRepository sagaRepo,
                          CompensationPlanWriter planWriter,
                          CompensationEngine compensationEngine,
                          ForwardExecutionEngine forwardEngine,
                          ObjectMapper objectMapper) {
        this.persistence       = persistence;
        this.executorFactory   = executorFactory;
        this.registry          = registry;
        this.sagaRepo          = sagaRepo;
        this.planWriter        = planWriter;
        this.compensationEngine = compensationEngine;
        this.forwardEngine     = forwardEngine;
        this.objectMapper      = objectMapper;
    }

    /**
     * Re-execute the timed-out step with the same idempotency key.
     *
     * @param exec the IN_PROGRESS step_execution past its timeout_at
     * @param saga the owning saga (status should be EXECUTING)
     */
    @SuppressWarnings("unchecked")
    public void handle(SagaStepExecution exec, SagaInstance saga) {
        UUID sagaId       = saga.getId();
        int  stepIndex    = exec.getStepIndex();
        SagaDefinition<?> definition = registry.lookup(saga.getSagaType());
        SagaStep step     = definition.stepAt(stepIndex);
        boolean isLastStep = (stepIndex == definition.stepCount() - 1);
        SagaContext context = deserializeContext(saga, definition);

        log.info("Timeout retry: saga={} step='{}' key={}", sagaId, step.name(), exec.getIdempotencyKey());

        // Re-execute with the SAME idempotency key — participant returns cached result if available
        ExecutionResult result = executorFactory.select(step).execute(step, context, exec.getIdempotencyKey());
        String contextJson = serializeContext(context);

        if (result instanceof ExecutionResult.Success success) {
            // Mark the IN_PROGRESS record COMPLETED and advance the saga
            persistence.persistStepSuccess(sagaId, exec.getId(), success.result(), contextJson, isLastStep, step.name());
            // Continue forward execution from the next step
            SagaInstance refreshed = sagaRepo.findById(sagaId).orElseThrow();
            if (!refreshed.getStatus().isTerminal()) {
                forwardEngine.executeFromStep(refreshed, definition, refreshed.getCurrentStep());
                refreshed = sagaRepo.findById(sagaId).orElseThrow();
                if (refreshed.getStatus() == SagaStatus.COMPENSATING) {
                    compensationEngine.compensate(refreshed, definition);
                }
            }

        } else if (result instanceof ExecutionResult.Failure failure) {
            log.warn("Timeout retry failed for saga={} step='{}': {}", sagaId, step.name(), failure.error());
            persistence.persistStepFailure(sagaId, exec.getId(), failure.error());
            triggerCompensationOrFail(sagaId, definition, stepIndex, step.name());

        } else {
            // Still timing out — extend deadline so the scanner doesn't re-fire immediately
            Instant extended = Instant.now().plus(step.timeout());
            log.warn("Timeout retry still timed out for saga={} step='{}', extending to {}", sagaId, step.name(), extended);
            persistence.extendStepTimeout(exec.getId(), extended);
        }
    }

    private void triggerCompensationOrFail(UUID sagaId, SagaDefinition<?> definition,
                                           int failedStepIndex, String stepName) {
        boolean hasCompensatables = IntStream.range(0, failedStepIndex)
                .anyMatch(i -> definition.stepAt(i).type() == StepType.COMPENSATABLE);
        if (hasCompensatables) {
            List<CompensationStep> plan = planWriter.buildPlan(sagaId, definition, failedStepIndex);
            persistence.transitionToCompensating(sagaId, plan, stepName);
            SagaInstance refreshed = sagaRepo.findById(sagaId).orElseThrow();
            compensationEngine.compensate(refreshed, definition);
        } else {
            persistence.transitionSaga(sagaId, SagaEvent.NO_COMPENSATABLES, stepName);
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

    private String serializeContext(SagaContext context) {
        try {
            return objectMapper.writeValueAsString(context);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize saga context", e);
        }
    }
}
