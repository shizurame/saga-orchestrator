package com.saga.orchestrator.engine;

import com.saga.orchestrator.definition.EntityLockConflictException;
import com.saga.orchestrator.definition.EntityRef;
import com.saga.orchestrator.definition.SagaStatus;
import com.saga.orchestrator.infrastructure.EntityLockManager;
import com.saga.orchestrator.infrastructure.HeartbeatService;
import com.saga.orchestrator.infrastructure.SagaLockManager;
import com.saga.orchestrator.persistence.entity.SagaInstance;
import com.saga.orchestrator.persistence.repository.SagaInstanceRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.OptionalLong;
import java.util.UUID;

/**
 * Transactional saga setup: locks + instance creation + claim + EXECUTING transition —
 * all in one atomic database transaction.
 *
 * Separated from SagaOrchestrator to avoid the Spring proxy self-invocation problem:
 * @Transactional only applies when called through the Spring proxy. If startSaga()
 * called a @Transactional method on itself (via 'this'), the proxy would be bypassed
 * and the method would run without a transaction.
 *
 * On EntityLockConflictException: the transaction rolls back, leaving no trace
 * in the database — the saga instance is never persisted.
 */
@Component
class SagaInitializer {

    private final EntityLockManager entityLockManager;
    private final SagaInstanceRepository sagaRepo;
    private final SagaLockManager sagaLockManager;
    private final HeartbeatService heartbeatService;

    SagaInitializer(EntityLockManager entityLockManager,
                    SagaInstanceRepository sagaRepo,
                    SagaLockManager sagaLockManager,
                    HeartbeatService heartbeatService) {
        this.entityLockManager = entityLockManager;
        this.sagaRepo          = sagaRepo;
        this.sagaLockManager   = sagaLockManager;
        this.heartbeatService  = heartbeatService;
    }

    /**
     * @throws EntityLockConflictException if any entity is held by another saga (→ 409 to caller)
     */
    @Transactional
    UUID initialize(String sagaType, String contextJson, List<EntityRef> lockedEntities) {
        UUID sagaId = UUID.randomUUID();

        // 1. Acquire entity locks — joins this TX. On conflict, exception rolls back everything.
        entityLockManager.acquireLocks(lockedEntities, sagaId);

        // 2. Create saga instance (STARTED)
        SagaInstance saga = sagaRepo.saveAndFlush(
                SagaInstance.createWithId(sagaId, sagaType, contextJson));

        // 3. Claim the saga — native UPDATE within this TX; succeeds because lock_owner IS NULL
        OptionalLong fencing = sagaLockManager.claimSaga(sagaId);
        if (fencing.isEmpty()) {
            throw new IllegalStateException("Failed to claim newly created saga: " + sagaId);
        }

        // 4. Transition to EXECUTING (directly — no state machine for init; STARTED→EXECUTING)
        saga.setStatus(SagaStatus.EXECUTING);
        sagaRepo.save(saga);

        // 5. Track for heartbeat renewal (outside any TX — in-memory only)
        heartbeatService.track(sagaId, fencing.getAsLong());

        return sagaId;
    }
}
