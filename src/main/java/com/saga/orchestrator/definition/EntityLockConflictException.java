package com.saga.orchestrator.definition;

/**
 * Thrown when a saga cannot acquire an entity lock because another saga holds it.
 * The API layer maps this to HTTP 409 Conflict; callers retry with exponential backoff.
 */
public class EntityLockConflictException extends RuntimeException {

    private final String entityType;
    private final String entityId;

    public EntityLockConflictException(String entityType, String entityId) {
        super("Entity lock held by another saga: " + entityType + "/" + entityId);
        this.entityType = entityType;
        this.entityId = entityId;
    }

    public String getEntityType() { return entityType; }
    public String getEntityId()   { return entityId; }
}
