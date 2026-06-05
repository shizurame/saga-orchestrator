package com.saga.orchestrator.integration;

import com.saga.orchestrator.definition.CompensationStatus;
import com.saga.orchestrator.definition.SagaStatus;
import com.saga.orchestrator.definition.StepExecutionStatus;
import com.saga.orchestrator.persistence.entity.*;
import com.saga.orchestrator.persistence.repository.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.OptionalLong;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PersistenceIntegrationTest extends AbstractIntegrationTest {

    @Autowired SagaInstanceRepository       sagaInstanceRepo;
    @Autowired SagaStepExecutionRepository  stepRepo;
    @Autowired CompensationStepRepository   compensationRepo;
    @Autowired SagaEntityLockRepository     lockRepo;
    @Autowired JdbcTemplate                 jdbc;

    @AfterEach
    void cleanup() {
        lockRepo.deleteAll();
        compensationRepo.deleteAll();
        stepRepo.deleteAll();
        sagaInstanceRepo.deleteAll();
    }

    // ── Round-trip tests ──────────────────────────────────────────────────────

    @Test
    void sagaInstance_writeAndRead() {
        SagaInstance saved = sagaInstanceRepo.save(newSaga());

        SagaInstance found = sagaInstanceRepo.findById(saved.getId()).orElseThrow();
        assertThat(found.getSagaType()).isEqualTo("fund-transfer");
        assertThat(found.getStatus()).isEqualTo(SagaStatus.STARTED);
        // PostgreSQL JSONB normalises JSON: adds space after colons and commas
        assertThat(found.getContext()).contains("\"amount\"").contains("100");
        assertThat(found.getVersion()).isEqualTo(0L);
        assertThat(found.getFencingToken()).isEqualTo(0L);
    }

    @Test
    void sagaStepExecution_writeAndRead() {
        SagaInstance saga = sagaInstanceRepo.save(newSaga());
        Instant timeout = Instant.now().plusSeconds(30);
        SagaStepExecution step = SagaStepExecution.createInProgress(
            saga.getId(), 0, 1, "key-abc-001", timeout);

        stepRepo.save(step);

        SagaStepExecution found = stepRepo.findById(step.getId()).orElseThrow();
        assertThat(found.getSagaId()).isEqualTo(saga.getId());
        assertThat(found.getStepIndex()).isEqualTo(0);
        assertThat(found.getAttemptNumber()).isEqualTo(1);
        assertThat(found.getIdempotencyKey()).isEqualTo("key-abc-001");
        assertThat(found.getStatus()).isEqualTo(StepExecutionStatus.IN_PROGRESS);
    }

    @Test
    void compensationStep_writeAndRead() {
        SagaInstance saga = sagaInstanceRepo.save(newSaga());
        CompensationStep cs = CompensationStep.pending(
            saga.getId(), 1, "release-funds", "comp-key-001");

        compensationRepo.save(cs);

        CompensationStep found = compensationRepo.findById(cs.getId()).orElseThrow();
        assertThat(found.getSagaId()).isEqualTo(saga.getId());
        assertThat(found.getStepIndex()).isEqualTo(1);
        assertThat(found.getAction()).isEqualTo("release-funds");
        assertThat(found.getStatus()).isEqualTo(CompensationStatus.PENDING);
        assertThat(found.getAttemptNumber()).isEqualTo(0);
    }

    @Test
    void sagaEntityLock_writeAndRead() {
        UUID sagaId = UUID.randomUUID();
        SagaEntityLock lock = new SagaEntityLock("ACCOUNT", "ACC-123", sagaId);

        lockRepo.save(lock);

        SagaEntityLock found = lockRepo.findById(new SagaEntityLock.EntityLockId("ACCOUNT", "ACC-123"))
            .orElseThrow();
        assertThat(found.getSagaId()).isEqualTo(sagaId);
        assertThat(found.getEntityType()).isEqualTo("ACCOUNT");
        assertThat(found.getEntityId()).isEqualTo("ACC-123");
    }

    // ── Optimistic locking ────────────────────────────────────────────────────

    @Test
    void optimisticLocking_staleWriteThrows() {
        SagaInstance saved = sagaInstanceRepo.save(newSaga());
        UUID id = saved.getId();

        // Load a fresh copy (version=0, detached)
        SagaInstance stale = sagaInstanceRepo.findById(id).orElseThrow();

        // Simulate concurrent update by bypassing JPA — version becomes 1 in DB
        jdbc.update("UPDATE saga_instance SET status='EXECUTING', version=version+1 WHERE id=?", id);

        // Attempt to save stale copy (version=0) — DB now has version=1
        stale.setStatus(SagaStatus.COMPENSATING);
        assertThatThrownBy(() -> sagaInstanceRepo.saveAndFlush(stale))
            .isInstanceOf(ObjectOptimisticLockingFailureException.class);
    }

    // ── Unique constraint on idempotency_key ──────────────────────────────────

    @Test
    void stepExecution_duplicateIdempotencyKey_throwsConstraintViolation() {
        SagaInstance saga = sagaInstanceRepo.save(newSaga());
        Instant timeout = Instant.now().plusSeconds(30);

        stepRepo.saveAndFlush(SagaStepExecution.createInProgress(
            saga.getId(), 0, 1, "duplicate-key", timeout));

        // Same key, different step — violates uq_idempotency_key
        SagaStepExecution duplicate = SagaStepExecution.createInProgress(
            saga.getId(), 1, 1, "duplicate-key", timeout);

        assertThatThrownBy(() -> stepRepo.saveAndFlush(duplicate))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void compensationStep_duplicateIdempotencyKey_throwsConstraintViolation() {
        SagaInstance saga = sagaInstanceRepo.save(newSaga());

        compensationRepo.saveAndFlush(
            CompensationStep.pending(saga.getId(), 0, "release", "comp-dup-key"));

        assertThatThrownBy(() -> compensationRepo.saveAndFlush(
            CompensationStep.pending(saga.getId(), 1, "release", "comp-dup-key")))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ── ON CONFLICT DO NOTHING ────────────────────────────────────────────────

    @Test
    void entityLock_onConflictDoNothing_doesNotOverwriteExistingLock() {
        UUID originalSagaId = UUID.randomUUID();
        UUID newcomerSagaId = UUID.randomUUID();

        lockRepo.save(new SagaEntityLock("ACCOUNT", "ACC-123", originalSagaId));

        // Simulate EntityLockManager's acquire: ON CONFLICT DO NOTHING
        // JdbcTemplate can't infer java.time.Instant — use java.sql.Timestamp explicitly
        jdbc.update(
            "INSERT INTO saga_entity_locks(entity_type, entity_id, saga_id, acquired_at) " +
            "VALUES (?, ?, ?, ?) ON CONFLICT (entity_type, entity_id) DO NOTHING",
            "ACCOUNT", "ACC-123", newcomerSagaId, Timestamp.from(Instant.now()));

        // Original saga still holds the lock
        SagaEntityLock existing = lockRepo.findById(
            new SagaEntityLock.EntityLockId("ACCOUNT", "ACC-123")).orElseThrow();
        assertThat(existing.getSagaId()).isEqualTo(originalSagaId);
    }

    // ── Recovery scanner query ────────────────────────────────────────────────

    @Test
    void findByStatusInAndLeaseExpiredBefore_returnsOnlyExpiredActiveSagas() {
        // Expired lease — should be returned
        SagaInstance expired = newSaga();
        expired.setStatus(SagaStatus.EXECUTING);
        expired.setLockOwner("dead-node");
        expired.setLeaseExpiresAt(Instant.now().minus(1, ChronoUnit.MINUTES));
        sagaInstanceRepo.save(expired);

        // Active lease — should NOT be returned
        SagaInstance active = newSaga();
        active.setStatus(SagaStatus.EXECUTING);
        active.setLockOwner("live-node");
        active.setLeaseExpiresAt(Instant.now().plus(30, ChronoUnit.SECONDS));
        sagaInstanceRepo.save(active);

        // Terminal saga with expired lease — should NOT be returned
        SagaInstance completed = newSaga();
        completed.setStatus(SagaStatus.COMPLETED);
        completed.setLeaseExpiresAt(Instant.now().minus(5, ChronoUnit.MINUTES));
        sagaInstanceRepo.save(completed);

        List<SagaInstance> orphans = sagaInstanceRepo.findByStatusInAndLeaseExpiresAtBefore(
            List.of(SagaStatus.EXECUTING, SagaStatus.COMPENSATING, SagaStatus.WAITING_FOR_CALLBACK),
            Instant.now());

        assertThat(orphans).hasSize(1);
        assertThat(orphans.get(0).getId()).isEqualTo(expired.getId());
    }

    // ── claimSaga native query ────────────────────────────────────────────────

    @Test
    void claimSaga_unclaimedSaga_returnsFencingToken() {
        SagaInstance saga = sagaInstanceRepo.save(newSaga()); // lockOwner = null
        Instant now = Instant.now();

        OptionalLong token = sagaInstanceRepo.claimSaga(
            saga.getId(), "node-1", now, now.plusSeconds(30));

        assertThat(token).isPresent();
        assertThat(token.getAsLong()).isEqualTo(1L); // fencing_token incremented from 0 to 1

        // Verify DB state
        jdbc.queryForList("SELECT lock_owner, fencing_token FROM saga_instance WHERE id=?",
            saga.getId()).forEach(row -> {
            assertThat(row.get("lock_owner")).isEqualTo("node-1");
            assertThat(((Number) row.get("fencing_token")).longValue()).isEqualTo(1L);
        });
    }

    @Test
    void claimSaga_activelyOwnedSaga_returnsEmpty() {
        SagaInstance saga = newSaga();
        saga.setLockOwner("node-already-owns");
        saga.setLeaseExpiresAt(Instant.now().plus(30, ChronoUnit.SECONDS));
        sagaInstanceRepo.save(saga);

        OptionalLong token = sagaInstanceRepo.claimSaga(
            saga.getId(), "node-2", Instant.now(), Instant.now().plusSeconds(30));

        assertThat(token).isEmpty(); // node-already-owns holds valid lease
    }

    @Test
    void compensationSteps_pendingInReverseOrder() {
        SagaInstance saga = sagaInstanceRepo.save(newSaga());

        compensationRepo.save(CompensationStep.pending(saga.getId(), 0, "release-check",  "key-0"));
        compensationRepo.save(CompensationStep.pending(saga.getId(), 1, "release-funds",  "key-1"));
        compensationRepo.save(CompensationStep.pending(saga.getId(), 2, "release-reserve","key-2"));

        List<CompensationStep> pending = compensationRepo
            .findBySagaIdAndStatusOrderByStepIndexDesc(saga.getId(), CompensationStatus.PENDING);

        assertThat(pending).hasSize(3);
        assertThat(pending.get(0).getStepIndex()).isEqualTo(2); // reverse: 2, 1, 0
        assertThat(pending.get(1).getStepIndex()).isEqualTo(1);
        assertThat(pending.get(2).getStepIndex()).isEqualTo(0);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private SagaInstance newSaga() {
        return SagaInstance.create("fund-transfer", "{\"amount\":100}");
    }
}
