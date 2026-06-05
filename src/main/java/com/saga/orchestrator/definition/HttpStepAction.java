package com.saga.orchestrator.definition;

/**
 * Marker interface for HTTP-backed step actions.
 *
 * Extending StepAction with this interface signals to the executor layer that
 * this action is implemented via an outbound HTTP call. The HttpStepExecutor
 * handles these: it injects the X-Idempotency-Key header automatically and
 * maps ResourceAccessException to ExecutionResult.TimedOut.
 *
 * Only HttpStepAction implementations may use TimeoutMode.WAIT_FOR_CALLBACK.
 * Bean actions are synchronous and structurally incapable of parking for an
 * async callback — the SagaDefinitionBuilder enforces this at build time.
 */
public interface HttpStepAction<C extends SagaContext> extends StepAction<C> {
}
