package com.saga.orchestrator.persistence.repository;

import com.saga.orchestrator.definition.StepExecutionStatus;
import com.saga.orchestrator.persistence.entity.SagaStepExecution;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SagaStepExecutionRepository extends JpaRepository<SagaStepExecution, UUID> {

    /**
     * Timeout scanner query: find all in-progress steps whose deadline has passed.
     * Only IN_PROGRESS steps are returned — TIMED_OUT steps (WAIT_FOR_CALLBACK mode)
     * are excluded since they are handled by the callback endpoint.
     */
    @Query("SELECT s FROM SagaStepExecution s WHERE s.status = 'IN_PROGRESS' AND s.timeoutAt < :now")
    List<SagaStepExecution> findTimedOutSteps(@Param("now") Instant now);

    Optional<SagaStepExecution> findBySagaIdAndStepIndexAndAttemptNumber(
            UUID sagaId, int stepIndex, int attemptNumber);

    /**
     * Callback handler query: find the step execution in a given status for a saga/step.
     * Used to locate the TIMED_OUT record when a callback arrives for a WAIT_FOR_CALLBACK step.
     */
    Optional<SagaStepExecution> findBySagaIdAndStepIndexAndStatus(
            UUID sagaId, int stepIndex, StepExecutionStatus status);

    /**
     * Extend the timeout deadline for a step that timed out but is still being retried.
     * timeout_at is updatable=false in the entity, so a native query is required.
     */
    @Modifying
    @Query(value = "UPDATE saga_step_executions SET timeout_at = :newTimeoutAt WHERE id = :executionId",
           nativeQuery = true)
    int extendTimeout(@Param("executionId") UUID executionId,
                      @Param("newTimeoutAt") Timestamp newTimeoutAt);

    List<SagaStepExecution> findBySagaIdOrderByStepIndexAscAttemptNumberAsc(UUID sagaId);
}
