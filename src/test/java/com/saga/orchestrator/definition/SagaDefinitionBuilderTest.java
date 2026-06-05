package com.saga.orchestrator.definition;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Duration;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SagaDefinitionBuilderTest {

    // ── Helpers ───────────────────────────────────────────────────────────────

    record TestContext(String value) implements SagaContext {}

    static StepAction<TestContext> beanAction() {
        return (ctx, key) -> new ExecutionResult.Success("ok");
    }

    static HttpStepAction<TestContext> httpAction() {
        return (ctx, key) -> new ExecutionResult.Success("ok");
    }

    private SagaDefinitionBuilder<TestContext> builder() {
        return SagaDefinition.named("test-saga", TestContext.class);
    }

    // ── Valid orderings ───────────────────────────────────────────────────────

    @Test
    void validOrdering_allCompensatable() {
        SagaDefinition<TestContext> def = builder()
            .compensatableStep("step-a").action(beanAction()).compensation(beanAction())
            .compensatableStep("step-b").action(beanAction()).compensation(beanAction())
            .build();

        assertThat(def.stepCount()).isEqualTo(2);
        assertThat(def.stepAt(0).type()).isEqualTo(StepType.COMPENSATABLE);
        assertThat(def.stepAt(1).type()).isEqualTo(StepType.COMPENSATABLE);
    }

    @Test
    void validOrdering_compensatableThenPivotThenRetryable() {
        SagaDefinition<TestContext> def = builder()
            .compensatableStep("check").action(beanAction()).compensation(beanAction())
            .pivotStep("debit").action(beanAction())
            .retryableStep("notify").action(beanAction())
            .build();

        assertThat(def.stepCount()).isEqualTo(3);
        assertThat(def.stepAt(0).type()).isEqualTo(StepType.COMPENSATABLE);
        assertThat(def.stepAt(1).type()).isEqualTo(StepType.PIVOT);
        assertThat(def.stepAt(2).type()).isEqualTo(StepType.RETRYABLE);
    }

    @Test
    void validOrdering_compensatableThenRetryable_noPivot() {
        SagaDefinition<TestContext> def = builder()
            .compensatableStep("reserve").action(beanAction()).compensation(beanAction())
            .retryableStep("notify").action(beanAction())
            .build();

        assertThat(def.stepCount()).isEqualTo(2);
        assertThat(def.stepAt(0).type()).isEqualTo(StepType.COMPENSATABLE);
        assertThat(def.stepAt(1).type()).isEqualTo(StepType.RETRYABLE);
    }

    @Test
    void validOrdering_allRetryable() {
        SagaDefinition<TestContext> def = builder()
            .retryableStep("step-1").action(beanAction())
            .retryableStep("step-2").action(beanAction())
            .build();

        assertThat(def.stepCount()).isEqualTo(2);
    }

    @Test
    void validOrdering_singlePivotOnly() {
        SagaDefinition<TestContext> def = builder()
            .pivotStep("only-step").action(beanAction())
            .build();

        assertThat(def.stepCount()).isEqualTo(1);
        assertThat(def.stepAt(0).type()).isEqualTo(StepType.PIVOT);
    }

    // ── Invalid orderings — the six forbidden transitions ─────────────────────

    @Test
    void invalid_compensatableAfterPivot() {
        assertThatThrownBy(() -> builder()
            .pivotStep("pivot").action(beanAction())
            .compensatableStep("late-comp").action(beanAction())
            .build())
            .isInstanceOf(SagaDefinitionException.class)
            .hasMessageContaining("COMPENSATABLE")
            .hasMessageContaining("PIVOT");
    }

    @Test
    void invalid_compensatableAfterRetryable() {
        assertThatThrownBy(() -> builder()
            .retryableStep("retry").action(beanAction())
            .compensatableStep("late-comp").action(beanAction())
            .build())
            .isInstanceOf(SagaDefinitionException.class)
            .hasMessageContaining("COMPENSATABLE")
            .hasMessageContaining("RETRYABLE");
    }

    @Test
    void invalid_pivotAfterPivot() {
        assertThatThrownBy(() -> builder()
            .pivotStep("pivot-1").action(beanAction())
            .pivotStep("pivot-2").action(beanAction())
            .build())
            .isInstanceOf(SagaDefinitionException.class)
            .hasMessageContaining("second PIVOT");
    }

    @Test
    void invalid_pivotAfterRetryable() {
        assertThatThrownBy(() -> builder()
            .retryableStep("retry").action(beanAction())
            .pivotStep("late-pivot").action(beanAction())
            .build())
            .isInstanceOf(SagaDefinitionException.class)
            .hasMessageContaining("PIVOT")
            .hasMessageContaining("RETRYABLE");
    }

    @Test
    void invalid_compensatableAfterPivotAndRetryable() {
        assertThatThrownBy(() -> builder()
            .compensatableStep("comp").action(beanAction()).compensation(beanAction())
            .pivotStep("pivot").action(beanAction())
            .retryableStep("retry").action(beanAction())
            .compensatableStep("too-late").action(beanAction())
            .build())
            .isInstanceOf(SagaDefinitionException.class);
    }

    @Test
    void invalid_secondPivotAfterCompensatableAndPivot() {
        assertThatThrownBy(() -> builder()
            .compensatableStep("comp").action(beanAction()).compensation(beanAction())
            .pivotStep("pivot-1").action(beanAction())
            .pivotStep("pivot-2").action(beanAction())
            .build())
            .isInstanceOf(SagaDefinitionException.class)
            .hasMessageContaining("second PIVOT");
    }

    // ── Other build-time validations ──────────────────────────────────────────

    @Test
    void invalid_noSteps() {
        assertThatThrownBy(() -> builder().build())
            .isInstanceOf(SagaDefinitionException.class)
            .hasMessageContaining("at least one step");
    }

    @Test
    void invalid_stepMissingAction() {
        assertThatThrownBy(() -> builder()
            .compensatableStep("no-action")
            // no .action() call
            .compensatableStep("next-step").action(beanAction())
            .build())
            .isInstanceOf(SagaDefinitionException.class)
            .hasMessageContaining("no action");
    }

    @Test
    void invalid_waitForCallbackOnBeanAction() {
        assertThatThrownBy(() -> builder()
            .pivotStep("debit")
                .action(beanAction())
                .timeout(Duration.ofSeconds(10), TimeoutMode.WAIT_FOR_CALLBACK)
            .build())
            .isInstanceOf(SagaDefinitionException.class)
            .hasMessageContaining("WAIT_FOR_CALLBACK")
            .hasMessageContaining("HttpStepAction");
    }

    @Test
    void valid_waitForCallbackOnHttpAction() {
        SagaDefinition<TestContext> def = builder()
            .pivotStep("debit")
                .action(httpAction())
                .timeout(Duration.ofSeconds(10), TimeoutMode.WAIT_FOR_CALLBACK)
            .build();

        assertThat(def.stepAt(0).timeoutMode()).isEqualTo(TimeoutMode.WAIT_FOR_CALLBACK);
    }

    // ── Step metadata preserved ───────────────────────────────────────────────

    @Test
    void stepMetadata_preservedCorrectly() {
        RetryPolicy policy = RetryPolicy.exponential(3, Duration.ofMillis(200));
        Duration timeout = Duration.ofSeconds(5);

        SagaDefinition<TestContext> def = builder()
            .compensatableStep("credit-check")
                .action(beanAction())
                .compensation(beanAction())
                .timeout(timeout, TimeoutMode.RETRY_WITH_IDEMPOTENCY)
                .retryPolicy(policy)
            .build();

        SagaStep step = def.stepAt(0);
        assertThat(step.name()).isEqualTo("credit-check");
        assertThat(step.type()).isEqualTo(StepType.COMPENSATABLE);
        assertThat(step.hasCompensation()).isTrue();
        assertThat(step.timeout()).isEqualTo(timeout);
        assertThat(step.timeoutMode()).isEqualTo(TimeoutMode.RETRY_WITH_IDEMPOTENCY);
        assertThat(step.retryPolicy()).isEqualTo(policy);
    }

    @Test
    void stepWithoutCompensation_hasCompensationIsFalse() {
        SagaDefinition<TestContext> def = builder()
            .pivotStep("debit").action(beanAction())
            .build();

        assertThat(def.stepAt(0).hasCompensation()).isFalse();
    }
}
