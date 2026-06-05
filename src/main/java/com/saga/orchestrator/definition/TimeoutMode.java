package com.saga.orchestrator.definition;

public enum TimeoutMode {

    /**
     * Treat the timeout as transient. Retry with the same idempotency key (same attempt number).
     * Safe when the participant honors X-Idempotency-Key — a duplicate call returns the cached result.
     */
    RETRY_WITH_IDEMPOTENCY,

    /**
     * Treat the timeout as a permanent step failure and trigger compensation.
     * Only safe if you are certain the call did not execute, or if the business cost of
     * a false compensation is acceptable.
     */
    TREAT_AS_FAILURE,

    /**
     * The step is genuinely async. Park the saga in WAITING_FOR_CALLBACK.
     * Do not retry; do not trigger compensation. Resume when the external system
     * posts to POST /sagas/{sagaId}/callback/{stepIndex}.
     * Only valid for HttpStepAction implementations; disallowed for bean steps.
     */
    WAIT_FOR_CALLBACK
}
