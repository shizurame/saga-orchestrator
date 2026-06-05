package com.saga.orchestrator.definition;

/**
 * Marker interface for all saga context objects.
 * Implementations carry the data passed between saga steps and must be
 * serializable to JSONB for persistence in saga_instance.context.
 */
public interface SagaContext {
}
