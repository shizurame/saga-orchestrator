package com.saga.orchestrator.persistence.entity;

import com.saga.orchestrator.definition.SagaStatus;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "saga_instance")
public class SagaInstance {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "saga_type", nullable = false, updatable = false)
    private String sagaType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SagaStatus status;

    @Column(name = "current_step", nullable = false)
    private int currentStep;

    // Stored as JSONB; the engine serializes/deserializes the context object
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private String context;

    // Managed by JPA — incremented automatically on every UPDATE
    @Version
    @Column(nullable = false)
    private Long version;

    @Column(name = "lock_owner")
    private String lockOwner;

    @Column(name = "heartbeat_at")
    private Instant heartbeatAt;

    @Column(name = "lease_expires_at")
    private Instant leaseExpiresAt;

    // NOT a @Version field — managed explicitly by the claimSaga native query
    @Column(name = "fencing_token", nullable = false)
    private Long fencingToken;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected SagaInstance() {}

    public static SagaInstance create(String sagaType, String contextJson) {
        return createWithId(UUID.randomUUID(), sagaType, contextJson);
    }

    /** Used by SagaInitializer to pre-allocate the ID before acquiring entity locks. */
    public static SagaInstance createWithId(UUID id, String sagaType, String contextJson) {
        SagaInstance i = new SagaInstance();
        i.id = id;
        i.sagaType = sagaType;
        i.status = SagaStatus.STARTED;
        i.currentStep = 0;
        i.context = contextJson;
        i.version = 0L;
        i.fencingToken = 0L;
        i.createdAt = Instant.now();
        i.updatedAt = Instant.now();
        return i;
    }

    // ── Getters ───────────────────────────────────────────────────────────────

    public UUID getId()              { return id; }
    public String getSagaType()      { return sagaType; }
    public SagaStatus getStatus()    { return status; }
    public int getCurrentStep()      { return currentStep; }
    public String getContext()       { return context; }
    public Long getVersion()         { return version; }
    public String getLockOwner()     { return lockOwner; }
    public Instant getHeartbeatAt()  { return heartbeatAt; }
    public Instant getLeaseExpiresAt() { return leaseExpiresAt; }
    public Long getFencingToken()    { return fencingToken; }
    public Instant getCreatedAt()    { return createdAt; }
    public Instant getUpdatedAt()    { return updatedAt; }

    // ── Setters for mutable fields only ──────────────────────────────────────

    public void setStatus(SagaStatus status)           { this.status = status; touch(); }
    public void setCurrentStep(int currentStep)        { this.currentStep = currentStep; touch(); }
    public void setContext(String context)             { this.context = context; touch(); }
    public void setLockOwner(String lockOwner)         { this.lockOwner = lockOwner; touch(); }
    public void setHeartbeatAt(Instant heartbeatAt)    { this.heartbeatAt = heartbeatAt; touch(); }
    public void setLeaseExpiresAt(Instant t)           { this.leaseExpiresAt = t; touch(); }
    public void setFencingToken(Long fencingToken)     { this.fencingToken = fencingToken; }

    private void touch() { this.updatedAt = Instant.now(); }
}
