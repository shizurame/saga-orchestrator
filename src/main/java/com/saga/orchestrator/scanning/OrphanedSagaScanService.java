package com.saga.orchestrator.scanning;

import com.saga.orchestrator.engine.SagaOrchestrator;
import com.saga.orchestrator.infrastructure.HeartbeatService;
import com.saga.orchestrator.infrastructure.SagaLockManager;
import com.saga.orchestrator.persistence.entity.SagaInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.OptionalLong;

/**
 * Scans for sagas whose lease has expired and resumes them on this node.
 *
 * A saga's lease expires when the node that was executing it crashes or loses
 * connectivity. The query finds EXECUTING, COMPENSATING, or WAITING_FOR_CALLBACK
 * sagas where lease_expires_at < NOW(). For each, it tries to claim the saga —
 * only one node wins (the claim is an atomic UPDATE). The winner tracks the saga
 * and calls orchestrator.resumeSaga() to continue execution.
 *
 * Called directly from OrphanedSagaScanner (Quartz Job). The public scan() method
 * is also injectable for testing without Quartz overhead.
 */
@Component
public class OrphanedSagaScanService {

    private static final Logger log = LoggerFactory.getLogger(OrphanedSagaScanService.class);

    private final SagaLockManager  sagaLockManager;
    private final HeartbeatService heartbeatService;
    private final SagaOrchestrator orchestrator;

    public OrphanedSagaScanService(SagaLockManager sagaLockManager,
                                   HeartbeatService heartbeatService,
                                   SagaOrchestrator orchestrator) {
        this.sagaLockManager  = sagaLockManager;
        this.heartbeatService = heartbeatService;
        this.orchestrator     = orchestrator;
    }

    public void scan() {
        List<SagaInstance> staleSagas = sagaLockManager.stealStaleSagas();
        if (!staleSagas.isEmpty()) {
            log.info("OrphanedSagaScanner found {} saga(s) with expired leases", staleSagas.size());
        }

        for (SagaInstance stale : staleSagas) {
            OptionalLong fencing = sagaLockManager.claimSaga(stale.getId());
            if (fencing.isEmpty()) {
                // Another node claimed it first — skip
                continue;
            }

            log.info("Claimed orphaned saga={} (fencingToken={})", stale.getId(), fencing.getAsLong());
            heartbeatService.track(stale.getId(), fencing.getAsLong());

            try {
                orchestrator.resumeSaga(stale.getId());
            } catch (Exception e) {
                log.error("Error resuming orphaned saga={}", stale.getId(), e);
            }
        }
    }
}
