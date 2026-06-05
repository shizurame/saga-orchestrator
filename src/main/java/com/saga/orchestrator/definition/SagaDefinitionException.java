package com.saga.orchestrator.definition;

/**
 * Thrown at saga definition build time when the step ordering invariant is violated
 * or a required configuration is missing. Surfaces as an application startup failure —
 * invalid sagas never reach runtime.
 */
public class SagaDefinitionException extends RuntimeException {

    public SagaDefinitionException(String message) {
        super(message);
    }
}
