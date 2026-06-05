package com.saga.orchestrator.persistence.repository;

import com.saga.orchestrator.definition.CompensationStatus;
import com.saga.orchestrator.persistence.entity.CompensationStep;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface CompensationStepRepository extends JpaRepository<CompensationStep, UUID> {

    /**
     * CompensationEngine query: load all PENDING compensation steps for a saga in reverse
     * step order. Executed in the compensation plan writer's partial-index range.
     */
    List<CompensationStep> findBySagaIdAndStatusOrderByStepIndexDesc(
            UUID sagaId, CompensationStatus status);

    /**
     * Crash-recovery query: loads PENDING and IN_PROGRESS steps in reverse order.
     * IN_PROGRESS means the orchestrator crashed between persistIntent and persistResult —
     * the step is re-executed with the same idempotency key.
     */
    List<CompensationStep> findBySagaIdAndStatusInOrderByStepIndexDesc(
            UUID sagaId, Collection<CompensationStatus> statuses);

    List<CompensationStep> findBySagaIdOrderByStepIndexAsc(UUID sagaId);
}
