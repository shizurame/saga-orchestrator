package com.saga.orchestrator.engine;

import com.saga.orchestrator.definition.ExecutionResult;
import com.saga.orchestrator.definition.SagaContext;
import com.saga.orchestrator.definition.SagaStep;
import com.saga.orchestrator.definition.StepAction;

public interface StepActionExecutor {

    /** Returns true if this executor handles the given action type. */
    boolean supports(StepAction<?> action);

    /**
     * Execute the step's action with the provided context and idempotency key.
     * Implementations must never throw — all outcomes are modelled as ExecutionResult variants.
     */
    ExecutionResult execute(SagaStep step, SagaContext context, String idempotencyKey);
}
