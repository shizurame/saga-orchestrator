package com.saga.orchestrator.definition;

import java.util.List;

/**
 * Immutable description of a saga: its type name, ordered steps, and the context class
 * used to deserialize the JSONB context column from saga_instance.
 *
 * Obtained exclusively via the builder: {@code SagaDefinition.named("my-saga", MyContext.class)...build()}.
 */
public record SagaDefinition<C extends SagaContext>(
        String sagaType,
        List<SagaStep> steps,
        Class<C> contextClass
) {
    public SagaDefinition {
        if (sagaType == null || sagaType.isBlank()) throw new IllegalArgumentException("sagaType must not be blank");
        if (steps == null || steps.isEmpty()) throw new IllegalArgumentException("steps must not be empty");
        if (contextClass == null) throw new IllegalArgumentException("contextClass must not be null");
        steps = List.copyOf(steps);
    }

    public SagaStep stepAt(int index) {
        return steps.get(index);
    }

    public int stepCount() {
        return steps.size();
    }

    /** Entry point for the fluent DSL. */
    public static <C extends SagaContext> SagaDefinitionBuilder<C> named(String sagaType, Class<C> contextClass) {
        return new SagaDefinitionBuilder<>(sagaType, contextClass);
    }
}
