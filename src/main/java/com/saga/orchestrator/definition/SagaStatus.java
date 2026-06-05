package com.saga.orchestrator.definition;

public enum SagaStatus {

    /** Created, not yet claimed by any orchestrator node. */
    STARTED,

    /** Forward steps are executing. */
    EXECUTING,

    /** A step declared WAIT_FOR_CALLBACK; saga is parked until the external callback arrives. */
    WAITING_FOR_CALLBACK,

    /** A step failed; compensation plan written; running compensations in reverse. */
    COMPENSATING,

    /** All forward steps succeeded. Business transaction committed. Terminal. */
    COMPLETED,

    /** All compensations succeeded. Business transaction rolled back cleanly. Terminal. */
    COMPENSATED,

    /**
     * A PIVOT step failed with no compensatable steps to undo,
     * OR the saga was rejected before execution began. Terminal.
     */
    FAILED,

    /** Compensation exhausted all retries. Manual intervention required. Terminal. */
    DEAD_LETTER;

    public boolean isTerminal() {
        return this == COMPLETED || this == COMPENSATED || this == FAILED || this == DEAD_LETTER;
    }
}
