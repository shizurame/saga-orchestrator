package com.saga.orchestrator.engine;

import com.saga.orchestrator.definition.SagaEvent;
import com.saga.orchestrator.definition.SagaStatus;
import org.springframework.stereotype.Component;

/**
 * Pure-function state machine: (current status, event) → next status.
 * Throws IllegalStateException on any invalid transition so the engine fails fast
 * rather than silently leaving a saga in an impossible state.
 */
@Component
public class SagaStateMachine {

    public SagaStatus transition(SagaStatus current, SagaEvent event) {
        return switch (current) {
            case STARTED -> switch (event) {
                case STEP_SUCCEEDED -> SagaStatus.EXECUTING;
                default             -> illegal(current, event);
            };
            case EXECUTING -> switch (event) {
                case STEP_SUCCEEDED    -> SagaStatus.EXECUTING;
                case SAGA_COMPLETED    -> SagaStatus.COMPLETED;
                case STEP_FAILED       -> SagaStatus.COMPENSATING;
                case NO_COMPENSATABLES -> SagaStatus.FAILED;
                case STEP_TIMED_OUT    -> SagaStatus.WAITING_FOR_CALLBACK;
                default                -> illegal(current, event);
            };
            case WAITING_FOR_CALLBACK -> switch (event) {
                case CALLBACK_SUCCESS -> SagaStatus.EXECUTING;
                case SAGA_COMPLETED   -> SagaStatus.COMPLETED;   // last step succeeded via callback
                case CALLBACK_FAILURE -> SagaStatus.COMPENSATING;
                default               -> illegal(current, event);
            };
            case COMPENSATING -> switch (event) {
                case COMPENSATION_SUCCEEDED -> SagaStatus.COMPENSATING;
                case ALL_COMPENSATED        -> SagaStatus.COMPENSATED;
                case COMPENSATION_FAILED    -> SagaStatus.COMPENSATING;
                case COMPENSATION_EXHAUSTED -> SagaStatus.DEAD_LETTER;
                default                     -> illegal(current, event);
            };
            case COMPLETED, COMPENSATED, FAILED, DEAD_LETTER -> illegal(current, event);
        };
    }

    private SagaStatus illegal(SagaStatus current, SagaEvent event) {
        throw new IllegalStateException(
                "Invalid saga transition: " + current + " + " + event);
    }
}
