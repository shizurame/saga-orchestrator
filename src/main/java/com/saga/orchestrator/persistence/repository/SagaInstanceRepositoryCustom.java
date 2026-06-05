package com.saga.orchestrator.persistence.repository;

import java.time.Instant;
import java.util.OptionalLong;
import java.util.UUID;

public interface SagaInstanceRepositoryCustom {

    /**
     * Atomically claim a saga if it is unclaimed or its lease has expired.
     * Increments fencing_token on success.
     *
     * @return the new fencing_token if the claim succeeded, empty if another node owns it
     */
    OptionalLong claimSaga(UUID sagaId, String nodeId, Instant heartbeatAt, Instant leaseExpiresAt);

    /**
     * Release ownership of a saga. The nodeId guard ensures a node cannot accidentally
     * release a lease that has already been stolen by another node.
     *
     * @return 1 if the release succeeded, 0 if the node was not the current owner
     */
    int releaseSaga(UUID sagaId, String nodeId);

    /**
     * Renew the heartbeat and lease expiry for a saga this node owns.
     * Returns 0 rows if another node stole the lease — the caller should remove
     * the saga from its owned-saga tracking map.
     *
     * @return 1 if renewed, 0 if this node is no longer the lock_owner
     */
    int renewLease(UUID sagaId, String nodeId, Instant heartbeatAt, Instant leaseExpiresAt);
}
