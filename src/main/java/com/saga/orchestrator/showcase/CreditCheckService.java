package com.saga.orchestrator.showcase;

import com.saga.orchestrator.definition.ExecutionResult;
import com.saga.orchestrator.definition.StepAction;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * COMPENSATABLE step 0: verify the customer has sufficient credit.
 *
 * Idempotency: results are cached by idempotency key — identical keys always return
 * the same result regardless of how many times execute/compensate are called.
 *
 * Behavior injection: tests can override the default success response via setBehavior()
 * to simulate failures without modifying production code.
 */
@Component
public class CreditCheckService {

    private final ConcurrentHashMap<String, ExecutionResult> forwardCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ExecutionResult> compCache    = new ConcurrentHashMap<>();
    private final AtomicReference<Supplier<ExecutionResult>> behavior     = new AtomicReference<>(this::defaultForward);

    public void setBehavior(Supplier<ExecutionResult> b) { behavior.set(b); }

    public void reset() {
        forwardCache.clear();
        compCache.clear();
        behavior.set(this::defaultForward);
    }

    public StepAction<TransferContext> forwardAction() {
        return (ctx, key) -> forwardCache.computeIfAbsent(key, k -> behavior.get().get());
    }

    public StepAction<TransferContext> compensationAction() {
        return (ctx, key) -> compCache.computeIfAbsent(key,
                k -> new ExecutionResult.Success(Map.of("creditHoldReleased", true)));
    }

    private ExecutionResult defaultForward() {
        return new ExecutionResult.Success(Map.of("creditApproved", true, "creditLimit", 50000));
    }
}
