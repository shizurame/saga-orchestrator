package com.saga.orchestrator.definition;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Spring bean that holds all registered SagaDefinitions.
 * The engine looks up definitions by saga type name at runtime.
 *
 * Registration happens at startup (e.g., from @PostConstruct in a saga configuration class).
 * Duplicate registration throws immediately — invalid configurations surface at startup.
 */
@Component
public class SagaDefinitionRegistry {

    private final ConcurrentMap<String, SagaDefinition<?>> registry = new ConcurrentHashMap<>();

    public void register(SagaDefinition<?> definition) {
        SagaDefinition<?> existing = registry.putIfAbsent(definition.sagaType(), definition);
        if (existing != null) {
            throw new SagaDefinitionException(
                "Saga type '" + definition.sagaType() + "' is already registered. " +
                "Each saga type may only be registered once");
        }
    }

    public SagaDefinition<?> lookup(String sagaType) {
        SagaDefinition<?> definition = registry.get(sagaType);
        if (definition == null) {
            throw new SagaDefinitionException(
                "No saga definition registered for type '" + sagaType + "'. " +
                "Ensure the saga is registered before the orchestrator is called");
        }
        return definition;
    }

    public boolean isRegistered(String sagaType) {
        return registry.containsKey(sagaType);
    }
}
