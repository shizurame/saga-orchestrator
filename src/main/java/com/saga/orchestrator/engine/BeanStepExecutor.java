package com.saga.orchestrator.engine;

import com.saga.orchestrator.definition.*;
import org.springframework.stereotype.Component;

import java.util.concurrent.*;

/**
 * Executes any non-HTTP StepAction on a virtual-thread worker pool.
 *
 * The action runs inside a CompletableFuture; the calling thread blocks on
 * future.get(timeout). This gives JVM-level timeout semantics — if the action
 * hangs, the thread is interrupted and ExecutionResult.TimedOut is returned,
 * identical to the timeout path for HTTP steps.
 *
 * All exceptions escaping action.execute() are caught and converted to
 * ExecutionResult.Failure so the engine never sees raw exceptions from step logic.
 */
@Component
public class BeanStepExecutor implements StepActionExecutor {

    private final ExecutorService workerPool = Executors.newVirtualThreadPerTaskExecutor();

    @Override
    public boolean supports(StepAction<?> action) {
        return !(action instanceof HttpStepAction);
    }

    @Override
    @SuppressWarnings("unchecked")
    public ExecutionResult execute(SagaStep step, SagaContext context, String idempotencyKey) {
        StepAction<SagaContext> action = (StepAction<SagaContext>) step.action();

        CompletableFuture<ExecutionResult> future = CompletableFuture.supplyAsync(() -> {
            try {
                return action.execute(context, idempotencyKey);
            } catch (Exception e) {
                String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getName();
                return new ExecutionResult.Failure(msg, e);
            }
        }, workerPool);

        try {
            return future.get(step.timeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            return ExecutionResult.TimedOut.INSTANCE;
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            String msg = cause.getMessage() != null ? cause.getMessage() : cause.getClass().getName();
            return new ExecutionResult.Failure(msg, cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ExecutionResult.Failure("Execution interrupted", e);
        }
    }
}
