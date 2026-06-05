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
 * COMPENSATABLE step 1: place a hold on source account funds.
 *
 * Forward: creates a reservation and stores the reservationId into the saga context
 * so downstream steps can reference it.
 *
 * Compensation: releases the reservation identified by ctx.getReservationId().
 * Idempotent on compensation key — safe to retry under crash-recovery.
 */
@Component
public class FundReservationService {

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
        return (ctx, key) -> {
            ExecutionResult result = forwardCache.computeIfAbsent(key, k -> behavior.get().get());
            if (result instanceof ExecutionResult.Success s && s.result() instanceof Map<?, ?> m) {
                ctx.setReservationId((String) m.get("reservationId"));
            }
            return result;
        };
    }

    public StepAction<TransferContext> compensationAction() {
        return (ctx, key) -> compCache.computeIfAbsent(key,
                k -> new ExecutionResult.Success(Map.of("reservationReleased", ctx.getReservationId())));
    }

    private ExecutionResult defaultForward() {
        String reservationId = "rsv-" + UUID.randomUUID().toString().substring(0, 8);
        return new ExecutionResult.Success(Map.of("reservationId", reservationId));
    }
}
