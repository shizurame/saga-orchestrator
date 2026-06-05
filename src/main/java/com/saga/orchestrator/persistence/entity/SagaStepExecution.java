package com.saga.orchestrator.persistence.entity;

import com.saga.orchestrator.definition.StepExecutionStatus;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
    name = "saga_step_executions",
    uniqueConstraints = {
        @UniqueConstraint(name = "uq_step_attempt",      columnNames = {"saga_id", "step_index", "attempt_number"}),
        @UniqueConstraint(name = "uq_idempotency_key",   columnNames = {"idempotency_key"})
    }
)
public class SagaStepExecution {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    // FK to saga_instance — no @ManyToOne to avoid accidental lazy-load joins in the hot path
    @Column(name = "saga_id", nullable = false, updatable = false)
    private UUID sagaId;

    @Column(name = "step_index", nullable = false, updatable = false)
    private int stepIndex;

    @Column(name = "attempt_number", nullable = false, updatable = false)
    private int attemptNumber;

    @Column(name = "idempotency_key", nullable = false, updatable = false)
    private String idempotencyKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private StepExecutionStatus status;

    @Column(name = "timeout_at", nullable = false, updatable = false)
    private Instant timeoutAt;

    @Column(name = "started_at", nullable = false, updatable = false)
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String result;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String error;

    protected SagaStepExecution() {}

    public static SagaStepExecution createInProgress(
            UUID sagaId, int stepIndex, int attemptNumber, String idempotencyKey, Instant timeoutAt) {
        SagaStepExecution e = new SagaStepExecution();
        e.id = UUID.randomUUID();
        e.sagaId = sagaId;
        e.stepIndex = stepIndex;
        e.attemptNumber = attemptNumber;
        e.idempotencyKey = idempotencyKey;
        e.status = StepExecutionStatus.IN_PROGRESS;
        e.timeoutAt = timeoutAt;
        e.startedAt = Instant.now();
        return e;
    }

    public UUID getId()                    { return id; }
    public UUID getSagaId()                { return sagaId; }
    public int getStepIndex()              { return stepIndex; }
    public int getAttemptNumber()          { return attemptNumber; }
    public String getIdempotencyKey()      { return idempotencyKey; }
    public StepExecutionStatus getStatus() { return status; }
    public Instant getTimeoutAt()          { return timeoutAt; }
    public Instant getStartedAt()          { return startedAt; }
    public Instant getCompletedAt()        { return completedAt; }
    public String getResult()              { return result; }
    public String getError()               { return error; }

    public void markCompleted(String resultJson) {
        this.status = StepExecutionStatus.COMPLETED;
        this.completedAt = Instant.now();
        this.result = resultJson;
    }

    public void markFailed(String errorJson) {
        this.status = StepExecutionStatus.FAILED;
        this.completedAt = Instant.now();
        this.error = errorJson;
    }

    public void markTimedOut() {
        this.status = StepExecutionStatus.TIMED_OUT;
        this.completedAt = Instant.now();
    }
}
