package com.saga.orchestrator.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.saga.orchestrator.definition.EntityRef;

import java.util.List;

/**
 * Request body for POST /sagas.
 *
 * context is a raw JSON object — the engine stores it as JSONB and deserializes it
 * into the saga's context class at execution time.
 *
 * lockedEntities: business entities to lock for this saga's duration. Callers must
 * declare all entities they will read-modify-write so the engine can prevent concurrent
 * sagas from operating on the same entity (preventing overdrafts, double-booking, etc.).
 */
public record StartSagaRequest(
        String sagaType,
        JsonNode context,
        List<EntityRef> lockedEntities
) {
    public StartSagaRequest {
        if (sagaType == null || sagaType.isBlank())
            throw new IllegalArgumentException("sagaType must not be blank");
        if (context == null)
            throw new IllegalArgumentException("context must not be null");
        lockedEntities = lockedEntities == null ? List.of() : List.copyOf(lockedEntities);
    }
}
