package com.saga.orchestrator.engine;

import com.saga.orchestrator.definition.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;

/**
 * Executes HttpStepAction implementations.
 *
 * HttpStepAction implementations are Spring beans that make their own HTTP calls
 * (typically via an injected RestClient) and receive the idempotency key as a parameter
 * which they forward as the X-Idempotency-Key header. The executor's role is to
 * map network-level exceptions to the correct ExecutionResult variant:
 *
 *   ResourceAccessException (connect/read timeout) → ExecutionResult.TimedOut
 *   HttpStatusCodeException (4xx / 5xx)            → ExecutionResult.Failure
 *   Any other exception                             → ExecutionResult.Failure
 *
 * Per-request network timeouts are configured on the RestClient inside each
 * HttpStepAction implementation so the step's timeout Duration is respected
 * at the HTTP client level.
 */
@Component
public class HttpStepExecutor implements StepActionExecutor {

    @Override
    public boolean supports(StepAction<?> action) {
        return action instanceof HttpStepAction;
    }

    @Override
    @SuppressWarnings("unchecked")
    public ExecutionResult execute(SagaStep step, SagaContext context, String idempotencyKey) {
        HttpStepAction<SagaContext> action = (HttpStepAction<SagaContext>) step.action();
        try {
            return action.execute(context, idempotencyKey);
        } catch (ResourceAccessException e) {
            return ExecutionResult.TimedOut.INSTANCE;
        } catch (HttpStatusCodeException e) {
            return new ExecutionResult.Failure(
                    e.getStatusCode().value() + ": " + e.getResponseBodyAsString(), e);
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getName();
            return new ExecutionResult.Failure(msg, e);
        }
    }
}
