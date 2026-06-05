package com.saga.orchestrator.definition;

/**
 * State machine triggers for saga status transitions.
 *
 * Design: each event maps unambiguously to exactly one next status given the current status.
 * Terminal-state transitions (COMPLETED, COMPENSATED, FAILED) use distinct events so the
 * engine never has to inspect external context to determine the next status.
 */
public enum SagaEvent {

    /** A forward step completed successfully; more steps remain — saga stays EXECUTING. */
    STEP_SUCCEEDED,

    /** The final forward step completed; no more steps — saga transitions to COMPLETED. */
    SAGA_COMPLETED,

    /** A forward step failed after exhausting retries; compensatable prior steps exist. */
    STEP_FAILED,

    /** A forward step (or PIVOT) failed with no completed compensatable steps to undo. */
    NO_COMPENSATABLES,

    /** Step timed out under WAIT_FOR_CALLBACK mode; saga parks until external callback. */
    STEP_TIMED_OUT,

    /** External callback arrived with a success result; saga resumes forward execution. */
    CALLBACK_SUCCESS,

    /** External callback arrived with a failure; compensation will begin. */
    CALLBACK_FAILURE,

    /** One compensation step completed; more remain — saga stays COMPENSATING. */
    COMPENSATION_SUCCEEDED,

    /** The final compensation step completed; saga transitions to COMPENSATED. */
    ALL_COMPENSATED,

    /** A compensation step failed within its retry budget; will be retried. */
    COMPENSATION_FAILED,

    /** Compensation exhausted all retries; saga transitions to DEAD_LETTER. */
    COMPENSATION_EXHAUSTED
}
