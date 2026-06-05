package com.saga.orchestrator.definition;

/**
 * Sealed result type returned by every StepAction and StepActionExecutor.
 * The engine pattern-matches on this to decide next state.
 */
public sealed interface ExecutionResult permits
        ExecutionResult.Success,
        ExecutionResult.Failure,
        ExecutionResult.TimedOut {

    record Success(Object result) implements ExecutionResult {}

    record Failure(String error, Throwable cause) implements ExecutionResult {
        public Failure(String error) {
            this(error, null);
        }
    }

    final class TimedOut implements ExecutionResult {
        public static final TimedOut INSTANCE = new TimedOut();
        private TimedOut() {}
    }
}
