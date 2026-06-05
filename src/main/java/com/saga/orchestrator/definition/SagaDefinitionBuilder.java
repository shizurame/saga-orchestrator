package com.saga.orchestrator.definition;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Fluent builder for SagaDefinition. Enforces the COMPENSATABLE* → PIVOT? → RETRYABLE*
 * ordering invariant at build time, not at runtime.
 *
 * Usage:
 * <pre>{@code
 *   SagaDefinition.named("fund-transfer", TransferContext.class)
 *       .compensatableStep("credit-check")
 *           .action(creditCheckAction)
 *           .compensation(creditReleaseAction)
 *           .timeout(Duration.ofSeconds(5), TimeoutMode.RETRY_WITH_IDEMPOTENCY)
 *           .retryPolicy(RetryPolicy.exponential(3, Duration.ofMillis(200)))
 *       .pivotStep("debit-account")
 *           .action(debitHttpAction)
 *           .timeout(Duration.ofSeconds(10), TimeoutMode.WAIT_FOR_CALLBACK)
 *       .retryableStep("send-notification")
 *           .action(notifyAction)
 *           .retryPolicy(RetryPolicy.fixed(5, Duration.ofSeconds(1)))
 *       .build();
 * }</pre>
 */
public final class SagaDefinitionBuilder<C extends SagaContext> {

    private enum BuilderPhase { INITIAL, COMPENSATABLE, PIVOT, RETRYABLE }

    private final String sagaType;
    private final Class<C> contextClass;
    private final List<SagaStep> steps = new ArrayList<>();

    // Per-step pending state
    private String pendingName;
    private StepType pendingType;
    @SuppressWarnings("rawtypes")
    private StepAction pendingAction;
    @SuppressWarnings("rawtypes")
    private StepAction pendingCompensation;
    private RetryPolicy pendingRetryPolicy;
    private Duration pendingTimeout;
    private TimeoutMode pendingTimeoutMode;
    private boolean hasPending = false;

    private BuilderPhase phase = BuilderPhase.INITIAL;

    SagaDefinitionBuilder(String sagaType, Class<C> contextClass) {
        this.sagaType = Objects.requireNonNull(sagaType, "sagaType");
        this.contextClass = Objects.requireNonNull(contextClass, "contextClass");
    }

    // ── Step declaration methods ──────────────────────────────────────────────

    public SagaDefinitionBuilder<C> compensatableStep(String name) {
        finalizeCurrentStep();
        if (phase == BuilderPhase.PIVOT || phase == BuilderPhase.RETRYABLE) {
            throw new SagaDefinitionException(
                "Cannot add COMPENSATABLE step '" + name + "' after " + phase +
                " phase in saga '" + sagaType + "'. " +
                "Required ordering: COMPENSATABLE* → PIVOT? → RETRYABLE*");
        }
        phase = BuilderPhase.COMPENSATABLE;
        resetPending(name, StepType.COMPENSATABLE);
        return this;
    }

    public SagaDefinitionBuilder<C> pivotStep(String name) {
        finalizeCurrentStep();
        if (phase == BuilderPhase.PIVOT) {
            throw new SagaDefinitionException(
                "Cannot add a second PIVOT step '" + name + "' in saga '" + sagaType +
                "'. A saga may have at most one PIVOT step.");
        }
        if (phase == BuilderPhase.RETRYABLE) {
            throw new SagaDefinitionException(
                "Cannot add PIVOT step '" + name + "' after RETRYABLE phase in saga '" + sagaType +
                "'. Required ordering: COMPENSATABLE* → PIVOT? → RETRYABLE*");
        }
        phase = BuilderPhase.PIVOT;
        resetPending(name, StepType.PIVOT);
        return this;
    }

    public SagaDefinitionBuilder<C> retryableStep(String name) {
        finalizeCurrentStep();
        phase = BuilderPhase.RETRYABLE;
        resetPending(name, StepType.RETRYABLE);
        return this;
    }

    // ── Step configuration methods ────────────────────────────────────────────

    public SagaDefinitionBuilder<C> action(StepAction<C> action) {
        requirePendingStep();
        this.pendingAction = Objects.requireNonNull(action, "action");
        return this;
    }

    public SagaDefinitionBuilder<C> compensation(StepAction<C> compensation) {
        requirePendingStep();
        this.pendingCompensation = Objects.requireNonNull(compensation, "compensation");
        return this;
    }

    public SagaDefinitionBuilder<C> timeout(Duration timeout, TimeoutMode mode) {
        requirePendingStep();
        this.pendingTimeout = Objects.requireNonNull(timeout, "timeout");
        this.pendingTimeoutMode = Objects.requireNonNull(mode, "timeoutMode");
        return this;
    }

    public SagaDefinitionBuilder<C> retryPolicy(RetryPolicy policy) {
        requirePendingStep();
        this.pendingRetryPolicy = Objects.requireNonNull(policy, "retryPolicy");
        return this;
    }

    // ── Terminal ──────────────────────────────────────────────────────────────

    public SagaDefinition<C> build() {
        finalizeCurrentStep();
        if (steps.isEmpty()) {
            throw new SagaDefinitionException("Saga '" + sagaType + "' must have at least one step");
        }
        return new SagaDefinition<>(sagaType, List.copyOf(steps), contextClass);
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    @SuppressWarnings("rawtypes")
    private void finalizeCurrentStep() {
        if (!hasPending) return;
        if (pendingAction == null) {
            throw new SagaDefinitionException(
                "Step '" + pendingName + "' in saga '" + sagaType + "' has no action configured. " +
                "Call .action(...) before declaring the next step or calling .build()");
        }
        // Bean actions (anything that is NOT an HttpStepAction) cannot park for a callback —
        // they are synchronous and structurally incapable of resuming from an async signal.
        if (pendingTimeoutMode == TimeoutMode.WAIT_FOR_CALLBACK
                && !(pendingAction instanceof HttpStepAction)) {
            throw new SagaDefinitionException(
                "Step '" + pendingName + "' in saga '" + sagaType +
                "': TimeoutMode.WAIT_FOR_CALLBACK requires an HttpStepAction. " +
                "Bean actions are synchronous — use RETRY_WITH_IDEMPOTENCY or TREAT_AS_FAILURE instead");
        }
        steps.add(new SagaStep(
            pendingName, pendingType, pendingAction, pendingCompensation,
            pendingRetryPolicy, pendingTimeout, pendingTimeoutMode
        ));
        hasPending = false;
    }

    private void resetPending(String name, StepType type) {
        pendingName = Objects.requireNonNull(name, "step name");
        if (name.isBlank()) throw new SagaDefinitionException("Step name must not be blank");
        pendingType = type;
        pendingAction = null;
        pendingCompensation = null;
        pendingRetryPolicy = RetryPolicy.noRetry();
        pendingTimeout = Duration.ofSeconds(30);
        pendingTimeoutMode = TimeoutMode.TREAT_AS_FAILURE;
        hasPending = true;
    }

    private void requirePendingStep() {
        if (!hasPending) {
            throw new SagaDefinitionException(
                "No step declared yet in saga '" + sagaType +
                "'. Call compensatableStep(), pivotStep(), or retryableStep() first");
        }
    }
}
