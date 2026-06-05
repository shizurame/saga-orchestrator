package com.saga.orchestrator.persistence.entity;

import jakarta.persistence.*;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "saga_entity_locks")
public class SagaEntityLock {

    @Embeddable
    public static class EntityLockId implements Serializable {

        @Column(name = "entity_type", nullable = false)
        private String entityType;

        @Column(name = "entity_id", nullable = false)
        private String entityId;

        protected EntityLockId() {}

        public EntityLockId(String entityType, String entityId) {
            this.entityType = Objects.requireNonNull(entityType);
            this.entityId   = Objects.requireNonNull(entityId);
        }

        public String getEntityType() { return entityType; }
        public String getEntityId()   { return entityId; }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof EntityLockId that)) return false;
            return Objects.equals(entityType, that.entityType)
                && Objects.equals(entityId,   that.entityId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(entityType, entityId);
        }
    }

    @EmbeddedId
    private EntityLockId id;

    @Column(name = "saga_id", nullable = false)
    private UUID sagaId;

    @Column(name = "acquired_at", nullable = false, updatable = false)
    private Instant acquiredAt;

    protected SagaEntityLock() {}

    public SagaEntityLock(String entityType, String entityId, UUID sagaId) {
        this.id = new EntityLockId(entityType, entityId);
        this.sagaId = Objects.requireNonNull(sagaId);
        this.acquiredAt = Instant.now();
    }

    public EntityLockId getId()    { return id; }
    public String getEntityType()  { return id.getEntityType(); }
    public String getEntityId()    { return id.getEntityId(); }
    public UUID getSagaId()        { return sagaId; }
    public Instant getAcquiredAt() { return acquiredAt; }
}
