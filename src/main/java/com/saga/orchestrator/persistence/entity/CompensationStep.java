package com.saga.orchestrator.persistence.entity;

import com.saga.orchestrator.definition.CompensationStatus;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.UUID;

@Entity
@Table(
    name = "compensation_steps",
    uniqueConstraints = {
        @UniqueConstraint(name = "uq_comp_step",            columnNames = {"saga_id", "step_index"}),
        @UniqueConstraint(name = "uq_comp_idempotency_key", columnNames = {"idempotency_key"})
    }
)
public class CompensationStep {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "saga_id", nullable = false, updatable = false)
    private UUID sagaId;

    @Column(name = "step_index", nullable = false, updatable = false)
    private int stepIndex;

    // Human-readable name of the compensation action (e.g. "release-funds")
    @Column(nullable = false, updatable = false)
    private String action;

    @Column(name = "idempotency_key", nullable = false, updatable = false)
    private String idempotencyKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CompensationStatus status;

    @Column(name = "attempt_number", nullable = false)
    private int attemptNumber;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String result;

    protected CompensationStep() {}

    public static CompensationStep pending(
            UUID sagaId, int stepIndex, String action, String idempotencyKey) {
        CompensationStep cs = new CompensationStep();
        cs.id = UUID.randomUUID();
        cs.sagaId = sagaId;
        cs.stepIndex = stepIndex;
        cs.action = action;
        cs.idempotencyKey = idempotencyKey;
        cs.status = CompensationStatus.PENDING;
        cs.attemptNumber = 0;
        return cs;
    }

    public UUID getId()                     { return id; }
    public UUID getSagaId()                 { return sagaId; }
    public int getStepIndex()               { return stepIndex; }
    public String getAction()               { return action; }
    public String getIdempotencyKey()       { return idempotencyKey; }
    public CompensationStatus getStatus()   { return status; }
    public int getAttemptNumber()           { return attemptNumber; }
    public String getResult()               { return result; }

    public void markInProgress()               { this.status = CompensationStatus.IN_PROGRESS; this.attemptNumber++; }
    public void markCompleted(String json)     { this.status = CompensationStatus.COMPLETED; this.result = json; }
    public void markFailed()                   { this.status = CompensationStatus.FAILED; }
}
