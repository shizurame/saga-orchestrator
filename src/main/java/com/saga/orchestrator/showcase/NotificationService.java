package com.saga.orchestrator.showcase;

import com.saga.orchestrator.definition.ExecutionResult;
import com.saga.orchestrator.definition.StepAction;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * RETRYABLE step 4: send a transfer confirmation notification.
 *
 * RETRYABLE with no compensation: failure is retried; idempotency ensures the customer
 * doesn't receive duplicate confirmations.
 */
@Component
public class NotificationService {

    private final ConcurrentHashMap<String, ExecutionResult> cache   = new ConcurrentHashMap<>();
    private final AtomicReference<Supplier<ExecutionResult>> behavior = new AtomicReference<>(this::defaultForward);

    public void setBehavior(Supplier<ExecutionResult> b) { behavior.set(b); }

    public void reset() {
        cache.clear();
        behavior.set(this::defaultForward);
    }

    public StepAction<TransferContext> forwardAction() {
        return (ctx, key) -> cache.computeIfAbsent(key, k -> behavior.get().get());
    }

    private ExecutionResult defaultForward() {
        return new ExecutionResult.Success(Map.of("notificationSent", true));
    }
}
