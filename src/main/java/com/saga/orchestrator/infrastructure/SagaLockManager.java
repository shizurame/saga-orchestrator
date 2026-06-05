package com.saga.orchestrator.infrastructure;

import com.saga.orchestrator.definition.SagaStatus;
import com.saga.orchestrator.persistence.entity.SagaInstance;
import com.saga.orchestrator.persistence.repository.SagaInstanceRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.OptionalLong;
import java.util.UUID;

/**
 * Manages node-level saga ownership: claiming, releasing, and finding orphaned sagas.
 * All lease math is centralised here so the rest of the engine never touches lease timestamps.
 */
@Component
public class SagaLockManager {

    private final SagaInstanceRepository sagaInstanceRepo;
    private final String nodeId;
    private final int leaseDurationSeconds;

    public SagaLockManager(SagaInstanceRepository sagaInstanceRepo,
                           @Value("${saga.orchestrator.node-id}") String nodeId,
                           @Value("${saga.orchestrator.lease-duration-seconds:30}") int leaseDurationSeconds) {
        this.sagaInstanceRepo = sagaInstanceRepo;
        this.nodeId = nodeId;
        this.leaseDurationSeconds = leaseDurationSeconds;
    }

    /**
     * Attempt to claim the given saga for this node.
     *
     * @return the new fencing token on success; empty if another node currently owns it
     */
    public OptionalLong claimSaga(UUID sagaId) {
        Instant now = Instant.now();
        return sagaInstanceRepo.claimSaga(sagaId, nodeId, now, now.plusSeconds(leaseDurationSeconds));
    }

    /**
     * Release this node's ownership of a saga (called at terminal state write time).
     * The nodeId guard ensures we do not accidentally release a lease another node stole.
     */
    public void releaseSaga(UUID sagaId) {
        sagaInstanceRepo.releaseSaga(sagaId, nodeId);
    }

    /**
     * Return all active sagas whose lease has expired — candidates for recovery.
     * The OrphanedSagaScanner (Phase 6) calls this, then attempts claimSaga on each.
     */
    public List<SagaInstance> stealStaleSagas() {
        return sagaInstanceRepo.findByStatusInAndLeaseExpiresAtBefore(
                List.of(SagaStatus.EXECUTING, SagaStatus.COMPENSATING, SagaStatus.WAITING_FOR_CALLBACK),
                Instant.now());
    }

    public String getNodeId() { return nodeId; }
}
