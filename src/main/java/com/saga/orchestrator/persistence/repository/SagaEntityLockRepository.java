package com.saga.orchestrator.persistence.repository;

import com.saga.orchestrator.persistence.entity.SagaEntityLock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

public interface SagaEntityLockRepository
        extends JpaRepository<SagaEntityLock, SagaEntityLock.EntityLockId> {

    /**
     * Release a specific saga's lock on an entity. The {@code sagaId} guard prevents a
     * saga from releasing a lock it does not own (defensive — shouldn't happen with correct
     * terminal-state transaction discipline, but included for safety).
     */
    @Modifying
    @Transactional
    @Query(value = """
            DELETE FROM saga_entity_locks
            WHERE entity_type = :entityType
              AND entity_id   = :entityId
              AND saga_id     = :sagaId
            """, nativeQuery = true)
    int releaselock(@Param("entityType") String entityType,
                    @Param("entityId")   String entityId,
                    @Param("sagaId")     UUID sagaId);
}
