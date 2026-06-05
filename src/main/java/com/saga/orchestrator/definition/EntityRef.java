package com.saga.orchestrator.definition;

import java.util.Objects;

/**
 * Identifies a business entity that must be locked for the duration of a saga.
 * Used in startSaga to declare which entities the saga will operate on.
 */
public record EntityRef(String entityType, String entityId) {

    public EntityRef {
        Objects.requireNonNull(entityType, "entityType must not be null");
        Objects.requireNonNull(entityId, "entityId must not be null");
    }
}
