package com.saga.orchestrator.engine;

import com.saga.orchestrator.definition.SagaDefinition;
import com.saga.orchestrator.definition.StepType;
import com.saga.orchestrator.infrastructure.IdempotencyKeyGenerator;
import com.saga.orchestrator.persistence.entity.CompensationStep;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Builds the compensation plan for a failed saga.
 *
 * Pure computation — no DB access. The caller (ForwardExecutionEngine) passes the result
 * to StepPersistenceService.transitionToCompensating(), which writes all records atomically
 * with the EXECUTING→COMPENSATING status transition.
 *
 * Only COMPENSATABLE steps at indices strictly less than failedStepIndex are included.
 * PIVOT steps represent the point of no return and have no compensation action.
 * RETRYABLE steps are never compensated (they always succeed or the saga moves to DEAD_LETTER).
 *
 * The list is returned in DESC step_index order (highest index first) so the compensation
 * engine executes them in the correct reverse sequence.
 */
@Component
class CompensationPlanWriter {

    private final IdempotencyKeyGenerator keyGenerator;

    CompensationPlanWriter(IdempotencyKeyGenerator keyGenerator) {
        this.keyGenerator = keyGenerator;
    }

    /**
     * Build the compensation plan for a saga whose forward execution failed at failedStepIndex.
     *
     * Compensation keys always use attempt=0. Compensation retries reuse the same key
     * (unlike forward retries which increment attempt_number). This means the participant
     * can cache the result and return it idempotently on duplicate calls.
     */
    List<CompensationStep> buildPlan(UUID sagaId, SagaDefinition<?> definition, int failedStepIndex) {
        List<CompensationStep> plan = new ArrayList<>();
        for (int i = failedStepIndex - 1; i >= 0; i--) {
            var step = definition.stepAt(i);
            if (step.type() == StepType.COMPENSATABLE) {
                String key = keyGenerator.compensationKey(sagaId, i, 0);
                plan.add(CompensationStep.pending(sagaId, i, step.name(), key));
            }
        }
        return plan;
    }
}
