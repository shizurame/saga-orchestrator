package com.saga.orchestrator.definition;

public enum StepType {

    /**
     * Can be undone by a corresponding compensation action.
     * Must appear before the PIVOT step in a saga definition.
     */
    COMPENSATABLE,

    /**
     * The point of no return. If it succeeds, the saga is committed to completing forward.
     * If it fails, all preceding COMPENSATABLE steps are compensated, but the PIVOT itself is not.
     * A saga may have at most one PIVOT step.
     */
    PIVOT,

    /**
     * Guaranteed to eventually succeed with sufficient retries.
     * No compensation is possible or needed. Must appear after the PIVOT step.
     */
    RETRYABLE
}
