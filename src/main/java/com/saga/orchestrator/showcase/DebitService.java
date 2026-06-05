package com.saga.orchestrator.showcase;

import com.saga.orchestrator.definition.ExecutionResult;
import com.saga.orchestrator.definition.HttpStepAction;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * PIVOT step 2: debit the source account.
 *
 * Implements HttpStepAction (required for WAIT_FOR_CALLBACK). In production, this
 * calls the core banking system, which processes asynchronously and delivers the result
 * via the callback endpoint. In tests, asyncMode=false causes it to return Success
 * synchronously, bypassing the WAIT_FOR_CALLBACK parking entirely.
 *
 * In asyncMode=true: always returns TimedOut → saga parks in WAITING_FOR_CALLBACK.
 * The test must deliver the callback via CallbackController or orchestrator.receiveCallback().
 *
 * Idempotency: cached by key (syncMode only — in asyncMode the saga parks before retrying).
 */
@Component
public class DebitService implements HttpStepAction<TransferContext> {

    private final ConcurrentHashMap<String, ExecutionResult> cache = new ConcurrentHashMap<>();
    private volatile boolean asyncMode = false;
    private final AtomicReference<Supplier<ExecutionResult>> behavior = new AtomicReference<>(this::defaultSync);

    public void setAsyncMode(boolean async) { this.asyncMode = async; }
    public void setBehavior(Supplier<ExecutionResult> b) { behavior.set(b); }

    public void reset() {
        cache.clear();
        asyncMode = false;
        behavior.set(this::defaultSync);
    }

    @Override
    public ExecutionResult execute(TransferContext ctx, String idempotencyKey) {
        if (asyncMode) {
            return ExecutionResult.TimedOut.INSTANCE;
        }
        ExecutionResult result = cache.computeIfAbsent(idempotencyKey, k -> behavior.get().get());
        if (result instanceof ExecutionResult.Success s && s.result() instanceof Map<?, ?> m) {
            ctx.setAuthorizationId((String) m.get("authorizationId"));
        }
        return result;
    }

    private ExecutionResult defaultSync() {
        String authId = "auth-" + UUID.randomUUID().toString().substring(0, 8);
        return new ExecutionResult.Success(Map.of("authorizationId", authId));
    }
}
