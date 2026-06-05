package com.saga.orchestrator.infrastructure;

import com.saga.orchestrator.persistence.repository.SagaInstanceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Collections;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Renews lease timestamps for all sagas owned by this node.
 *
 * If renewLease returns 0 rows for a saga, another node has stolen the lease —
 * the saga is removed from the owned set and a warning is logged so the engine
 * can stop issuing writes for it (fencing token mismatch will reject any stale writes
 * anyway, but early detection reduces wasted work).
 */
@Service
public class HeartbeatService {

    private static final Logger log = LoggerFactory.getLogger(HeartbeatService.class);

    // sagaId → fencing token at the time this node claimed the saga
    private final ConcurrentHashMap<UUID, Long> ownedSagas = new ConcurrentHashMap<>();

    private final SagaInstanceRepository sagaInstanceRepo;
    private final String nodeId;
    private final int leaseDurationSeconds;

    public HeartbeatService(SagaInstanceRepository sagaInstanceRepo,
                            @Value("${saga.orchestrator.node-id}") String nodeId,
                            @Value("${saga.orchestrator.lease-duration-seconds:30}") int leaseDurationSeconds) {
        this.sagaInstanceRepo = sagaInstanceRepo;
        this.nodeId = nodeId;
        this.leaseDurationSeconds = leaseDurationSeconds;
    }

    /** Register a saga as owned by this node after a successful claim. */
    public void track(UUID sagaId, long fencingToken) {
        ownedSagas.put(sagaId, fencingToken);
    }

    /** Remove a saga from tracking (called when the saga reaches a terminal state). */
    public void untrack(UUID sagaId) {
        ownedSagas.remove(sagaId);
    }

    /** Fencing token stored at claim time — used by the engine for write-ordering checks. */
    public long fencingToken(UUID sagaId) {
        Long token = ownedSagas.get(sagaId);
        if (token == null) throw new IllegalStateException("Saga not tracked by this node: " + sagaId);
        return token;
    }

    public Set<UUID> ownedSagaIds() {
        return Collections.unmodifiableSet(ownedSagas.keySet());
    }

    /**
     * Renews heartbeat and lease for all owned sagas.
     * Fires every heartbeat-interval-seconds (default 10 s).
     *
     * The SpEL expression converts the seconds property to milliseconds for fixedDelayString.
     */
    @Scheduled(fixedDelayString = "#{${saga.orchestrator.heartbeat-interval-seconds:10} * 1000}")
    public void renewLeases() {
        if (ownedSagas.isEmpty()) return;

        Instant now       = Instant.now();
        Instant newExpiry = now.plusSeconds(leaseDurationSeconds);

        ownedSagas.forEach((sagaId, fencingToken) -> {
            int rows = sagaInstanceRepo.renewLease(sagaId, nodeId, now, newExpiry);
            if (rows == 0) {
                ownedSagas.remove(sagaId);
                log.warn("Lost ownership of saga {} — another node acquired the lease (fencing token was {})",
                        sagaId, fencingToken);
            }
        });
    }
}
