package com.saga.orchestrator.persistence.repository;

import com.saga.orchestrator.definition.SagaStatus;
import com.saga.orchestrator.persistence.entity.SagaInstance;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface SagaInstanceRepository
        extends JpaRepository<SagaInstance, UUID>, SagaInstanceRepositoryCustom {

    /**
     * Recovery scanner query: find sagas whose lease has expired (owner crashed).
     * Filtered to active statuses only — terminal sagas are never recovered.
     */
    List<SagaInstance> findByStatusInAndLeaseExpiresAtBefore(
            Collection<SagaStatus> statuses, Instant cutoff);

    List<SagaInstance> findByStatus(SagaStatus status);

    long countByStatus(SagaStatus status);
}
