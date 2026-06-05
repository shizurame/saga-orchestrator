package com.saga.orchestrator.definition;

import java.time.Duration;

/**
 * Immutable description of one step in a saga.
 * Type safety for action/compensation is enforced by SagaDefinitionBuilder at build time;
 * at runtime the engine holds raw StepAction and casts context appropriately.
 */
@SuppressWarnings("rawtypes")
public record SagaStep(
        String name,
        StepType type,
        StepAction action,
        StepAction compensation,
        RetryPolicy retryPolicy,
        Duration timeout,
        TimeoutMode timeoutMode
) {
    public SagaStep {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("Step name must not be blank");
        if (type == null) throw new IllegalArgumentException("StepType must not be null");
        if (action == null) throw new IllegalArgumentException("action must not be null");
        if (retryPolicy == null) throw new IllegalArgumentException("retryPolicy must not be null");
        if (timeout == null || timeout.isNegative()) throw new IllegalArgumentException("timeout must not be null or negative");
        if (timeoutMode == null) throw new IllegalArgumentException("timeoutMode must not be null");
    }

    public boolean hasCompensation() {
        return compensation != null;
    }
}
