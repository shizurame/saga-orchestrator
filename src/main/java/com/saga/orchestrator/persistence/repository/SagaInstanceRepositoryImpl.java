package com.saga.orchestrator.persistence.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.OptionalLong;
import java.util.UUID;

public class SagaInstanceRepositoryImpl implements SagaInstanceRepositoryCustom {

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    @Transactional
    public OptionalLong claimSaga(UUID sagaId, String nodeId, Instant heartbeatAt, Instant leaseExpiresAt) {
        // RETURNING is PostgreSQL-specific and requires a native query.
        // A @Modifying/@Query with RETURNING is unreliable in Spring Data JPA —
        // using EntityManager.createNativeQuery() is the correct approach.
        @SuppressWarnings("unchecked")
        List<Number> rows = entityManager.createNativeQuery("""
                UPDATE saga_instance
                SET lock_owner       = :nodeId,
                    heartbeat_at     = :heartbeatAt,
                    lease_expires_at = :leaseExpiresAt,
                    fencing_token    = fencing_token + 1,
                    updated_at       = NOW()
                WHERE id = :sagaId
                  AND (lock_owner IS NULL OR lease_expires_at < NOW())
                RETURNING fencing_token
                """)
            .setParameter("sagaId", sagaId)
            .setParameter("nodeId", nodeId)
            .setParameter("heartbeatAt", heartbeatAt)
            .setParameter("leaseExpiresAt", leaseExpiresAt)
            .getResultList();

        return rows.isEmpty()
            ? OptionalLong.empty()
            : OptionalLong.of(rows.get(0).longValue());
    }

    @Override
    @Transactional
    public int releaseSaga(UUID sagaId, String nodeId) {
        return entityManager.createNativeQuery("""
                UPDATE saga_instance
                SET lock_owner       = NULL,
                    heartbeat_at     = NULL,
                    lease_expires_at = NULL,
                    updated_at       = NOW()
                WHERE id         = :sagaId
                  AND lock_owner = :nodeId
                """)
            .setParameter("sagaId", sagaId)
            .setParameter("nodeId", nodeId)
            .executeUpdate();
    }

    @Override
    @Transactional
    public int renewLease(UUID sagaId, String nodeId, Instant heartbeatAt, Instant leaseExpiresAt) {
        return entityManager.createNativeQuery("""
                UPDATE saga_instance
                SET heartbeat_at     = :heartbeatAt,
                    lease_expires_at = :leaseExpiresAt,
                    updated_at       = NOW()
                WHERE id         = :sagaId
                  AND lock_owner = :nodeId
                """)
            .setParameter("sagaId", sagaId)
            .setParameter("nodeId", nodeId)
            .setParameter("heartbeatAt", heartbeatAt)
            .setParameter("leaseExpiresAt", leaseExpiresAt)
            .executeUpdate();
    }
}
