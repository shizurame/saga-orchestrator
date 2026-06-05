package com.saga.orchestrator.showcase;

import com.saga.orchestrator.definition.ExecutionResult;
import com.saga.orchestrator.definition.StepAction;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * RETRYABLE step 3: credit the destination account.
 *
 * Stores the transferId into the context for downstream steps.
 * RETRYABLE: no compensation. If this step fails after the pivot (debit), retrying
 * is the only option — the funds are already debited.
 */
@Component
public class CreditService {

    private final ConcurrentHashMap<String, ExecutionResult> cache   = new ConcurrentHashMap<>();
    private final AtomicReference<Supplier<ExecutionResult>> behavior = new AtomicReference<>(this::defaultForward);

    public void setBehavior(Supplier<ExecutionResult> b) { behavior.set(b); }

    public void reset() {
        cache.clear();
        behavior.set(this::defaultForward);
    }

    public StepAction<TransferContext> forwardAction() {
        return (ctx, key) -> {
            ExecutionResult result = cache.computeIfAbsent(key, k -> behavior.get().get());
            if (result instanceof ExecutionResult.Success s && s.result() instanceof Map<?, ?> m) {
                ctx.setTransferId((String) m.get("transferId"));
            }
            return result;
        };
    }

    private ExecutionResult defaultForward() {
        String transferId = "txn-" + UUID.randomUUID().toString().substring(0, 8);
        return new ExecutionResult.Success(Map.of("transferId", transferId));
    }
}
