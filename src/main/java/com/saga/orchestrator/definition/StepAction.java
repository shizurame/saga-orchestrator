package com.saga.orchestrator.definition;

@FunctionalInterface
public interface StepAction<C extends SagaContext> {

    /**
     * Execute this step.
     *
     * @param context       the saga context carrying state between steps
     * @param idempotencyKey deterministic key derived from (sagaId, stepIndex, attemptNumber);
     *                       the implementation must check-and-return-cached for bean actions,
     *                       or the executor injects it as X-Idempotency-Key for HTTP actions
     */
    ExecutionResult execute(C context, String idempotencyKey);
}
