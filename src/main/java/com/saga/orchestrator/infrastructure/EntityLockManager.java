package com.saga.orchestrator.infrastructure;

import com.saga.orchestrator.definition.EntityLockConflictException;
import com.saga.orchestrator.definition.EntityRef;
import com.saga.orchestrator.persistence.repository.SagaEntityLockRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Manages entity-level saga serialization via the saga_entity_locks table.
 *
 * Lock acquisition uses INSERT … ON CONFLICT DO NOTHING so competing sagas
 * never block each other — one immediately fails with EntityLockConflictException
 * rather than queueing internally.
 *
 * Canonical sort (lexicographic on entityType then entityId) is applied before
 * acquiring locks. This means any two sagas that declare the same set of entities
 * always try to acquire them in the same order, preventing deadlocks at the
 * PostgreSQL row-lock level even under heavy concurrency.
 */
@Component
public class EntityLockManager {

    private final JdbcTemplate jdbcTemplate;
    private final SagaEntityLockRepository lockRepo;

    public EntityLockManager(JdbcTemplate jdbcTemplate, SagaEntityLockRepository lockRepo) {
        this.jdbcTemplate = jdbcTemplate;
        this.lockRepo = lockRepo;
    }

    /**
     * Acquire locks on all listed entities for the given saga, atomically.
     *
     * All-or-nothing: if any entity is already locked by another saga,
     * a rollback releases every lock acquired in this call and
     * EntityLockConflictException is thrown.
     *
     * @throws EntityLockConflictException if any entity is held by another saga
     */
    @Transactional
    public void acquireLocks(List<EntityRef> entities, UUID sagaId) {
        if (entities == null || entities.isEmpty()) return;

        List<EntityRef> sorted = entities.stream()
                .sorted(Comparator.comparing(EntityRef::entityType)
                        .thenComparing(EntityRef::entityId))
                .toList();

        Timestamp now = Timestamp.from(Instant.now());
        for (EntityRef ref : sorted) {
            jdbcTemplate.update(
                    "INSERT INTO saga_entity_locks(entity_type, entity_id, saga_id, acquired_at) " +
                    "VALUES (?, ?, ?, ?) ON CONFLICT (entity_type, entity_id) DO NOTHING",
                    ref.entityType(), ref.entityId(), sagaId, now);
        }

        // Verify every lock is now ours. Throwing EntityLockConflictException (a RuntimeException)
        // causes @Transactional to roll back, releasing any inserts we performed above.
        for (EntityRef ref : sorted) {
            UUID owner = jdbcTemplate.queryForObject(
                    "SELECT saga_id FROM saga_entity_locks WHERE entity_type = ? AND entity_id = ?",
                    (rs, n) -> rs.getObject(1, UUID.class),
                    ref.entityType(), ref.entityId());
            if (!sagaId.equals(owner)) {
                throw new EntityLockConflictException(ref.entityType(), ref.entityId());
            }
        }
    }

    /**
     * Release a single entity lock. Must be called within the same transaction
     * as the saga's terminal-state write so the lock is never left orphaned.
     */
    @Transactional
    public void releaseLock(String entityType, String entityId, UUID sagaId) {
        lockRepo.releaselock(entityType, entityId, sagaId);
    }

    /**
     * Release all listed entity locks in a single transaction.
     * Convenience method for use at saga completion/compensation.
     */
    @Transactional
    public void releaseAllLocks(List<EntityRef> entities, UUID sagaId) {
        if (entities == null || entities.isEmpty()) return;
        for (EntityRef ref : entities) {
            lockRepo.releaselock(ref.entityType(), ref.entityId(), sagaId);
        }
    }

    /**
     * Release all entity locks held by this saga, by saga_id.
     *
     * Called from StepPersistenceService.releaseLocksAndTransition() within a
     * REQUIRES_NEW transaction, so this participates in that TX via REQUIRED propagation.
     * Lock release and terminal status update are therefore atomic — no orphaned locks.
     */
    @Transactional
    public void releaseAllLocksForSaga(UUID sagaId) {
        jdbcTemplate.update("DELETE FROM saga_entity_locks WHERE saga_id = ?", sagaId);
    }
}
