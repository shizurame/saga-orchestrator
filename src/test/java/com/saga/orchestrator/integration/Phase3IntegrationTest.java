package com.saga.orchestrator.integration;

import com.saga.orchestrator.definition.EntityLockConflictException;
import com.saga.orchestrator.definition.EntityRef;
import com.saga.orchestrator.definition.SagaStatus;
import com.saga.orchestrator.infrastructure.EntityLockManager;
import com.saga.orchestrator.infrastructure.HeartbeatService;
import com.saga.orchestrator.infrastructure.SagaLockManager;
import com.saga.orchestrator.persistence.entity.SagaEntityLock;
import com.saga.orchestrator.persistence.entity.SagaInstance;
import com.saga.orchestrator.persistence.repository.SagaEntityLockRepository;
import com.saga.orchestrator.persistence.repository.SagaInstanceRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

class Phase3IntegrationTest extends AbstractIntegrationTest {

    @Autowired SagaInstanceRepository   sagaInstanceRepo;
    @Autowired SagaEntityLockRepository lockRepo;
    @Autowired SagaLockManager          sagaLockManager;
    @Autowired EntityLockManager        entityLockManager;
    @Autowired HeartbeatService         heartbeatService;

    @AfterEach
    void cleanup() {
        lockRepo.deleteAll();
        sagaInstanceRepo.deleteAll();
    }

    // ── SagaLockManager ────────────────────────────────────────────────────────

    @Test
    void claimSaga_unowned_returnsFencingToken() {
        SagaInstance saga = sagaInstanceRepo.save(SagaInstance.create("fund-transfer", "{}"));

        OptionalLong token = sagaLockManager.claimSaga(saga.getId());

        assertThat(token).isPresent();
        assertThat(token.getAsLong()).isEqualTo(1L);
    }

    @Test
    void releaseSaga_clearsLockOwnerAndLease() {
        SagaInstance saga = sagaInstanceRepo.save(SagaInstance.create("fund-transfer", "{}"));
        sagaLockManager.claimSaga(saga.getId());

        sagaLockManager.releaseSaga(saga.getId());

        SagaInstance found = sagaInstanceRepo.findById(saga.getId()).orElseThrow();
        assertThat(found.getLockOwner()).isNull();
        assertThat(found.getLeaseExpiresAt()).isNull();
        assertThat(found.getHeartbeatAt()).isNull();
    }

    @Test
    void releaseSaga_wrongNode_doesNotRelease() {
        // Manually set lock_owner to "other-node"
        SagaInstance saga = SagaInstance.create("fund-transfer", "{}");
        saga.setLockOwner("other-node");
        saga.setLeaseExpiresAt(Instant.now().plusSeconds(30));
        sagaInstanceRepo.save(saga);

        // Our node tries to release — should be a no-op (wrong nodeId guard)
        sagaLockManager.releaseSaga(saga.getId());

        SagaInstance found = sagaInstanceRepo.findById(saga.getId()).orElseThrow();
        assertThat(found.getLockOwner()).isEqualTo("other-node"); // unchanged
    }

    @Test
    void stealStaleSagas_returnsExpiredActiveSagasOnly() {
        // Expired lease → should be returned
        SagaInstance expired = SagaInstance.create("fund-transfer", "{}");
        expired.setStatus(SagaStatus.EXECUTING);
        expired.setLockOwner("dead-node");
        expired.setLeaseExpiresAt(Instant.now().minus(1, ChronoUnit.MINUTES));
        sagaInstanceRepo.save(expired);

        // Valid lease → must NOT be returned
        SagaInstance active = SagaInstance.create("fund-transfer", "{}");
        active.setStatus(SagaStatus.EXECUTING);
        active.setLockOwner("live-node");
        active.setLeaseExpiresAt(Instant.now().plus(30, ChronoUnit.SECONDS));
        sagaInstanceRepo.save(active);

        // Terminal saga → must NOT be returned
        SagaInstance completed = SagaInstance.create("fund-transfer", "{}");
        completed.setStatus(SagaStatus.COMPLETED);
        completed.setLeaseExpiresAt(Instant.now().minus(5, ChronoUnit.MINUTES));
        sagaInstanceRepo.save(completed);

        List<SagaInstance> stale = sagaLockManager.stealStaleSagas();

        assertThat(stale).hasSize(1);
        assertThat(stale.get(0).getId()).isEqualTo(expired.getId());
    }

    // ── HeartbeatService ───────────────────────────────────────────────────────

    @Test
    void renewLeases_updatesLeaseExpiryInDb() {
        SagaInstance saga = sagaInstanceRepo.save(SagaInstance.create("fund-transfer", "{}"));
        OptionalLong token = sagaLockManager.claimSaga(saga.getId());
        assertThat(token).isPresent();

        heartbeatService.track(saga.getId(), token.getAsLong());
        heartbeatService.renewLeases();

        SagaInstance updated = sagaInstanceRepo.findById(saga.getId()).orElseThrow();
        // Lease was refreshed — should be ~30 s in the future
        assertThat(updated.getLeaseExpiresAt()).isAfter(Instant.now().plusSeconds(20));

        heartbeatService.untrack(saga.getId()); // cleanup
    }

    @Test
    void renewLeases_removesFromTrackingWhenLeaseStolen() {
        SagaInstance saga = sagaInstanceRepo.save(SagaInstance.create("fund-transfer", "{}"));
        sagaLockManager.claimSaga(saga.getId());
        heartbeatService.track(saga.getId(), 1L);

        // Simulate another node stealing the lease (clear lock_owner)
        sagaInstanceRepo.releaseSaga(saga.getId(), sagaLockManager.getNodeId());

        // renewLeases should find 0 rows updated for this saga and remove it from tracking
        heartbeatService.renewLeases();

        assertThat(heartbeatService.ownedSagaIds()).doesNotContain(saga.getId());
    }

    // ── EntityLockManager ─────────────────────────────────────────────────────

    @Test
    void acquireLocks_noConflict_allLocksStored() {
        UUID sagaId = UUID.randomUUID();
        List<EntityRef> entities = List.of(
                new EntityRef("ACCOUNT", "ACC-001"),
                new EntityRef("ACCOUNT", "ACC-002"));

        entityLockManager.acquireLocks(entities, sagaId);

        Optional<SagaEntityLock> l1 = lockRepo.findById(new SagaEntityLock.EntityLockId("ACCOUNT", "ACC-001"));
        Optional<SagaEntityLock> l2 = lockRepo.findById(new SagaEntityLock.EntityLockId("ACCOUNT", "ACC-002"));
        assertThat(l1).isPresent();
        assertThat(l1.get().getSagaId()).isEqualTo(sagaId);
        assertThat(l2).isPresent();
        assertThat(l2.get().getSagaId()).isEqualTo(sagaId);
    }

    @Test
    void acquireLocks_conflict_throwsAndRollsBackAcquired() {
        UUID holder   = UUID.randomUUID();
        UUID newcomer = UUID.randomUUID();

        // ACC-001 pre-held by another saga
        lockRepo.save(new SagaEntityLock("ACCOUNT", "ACC-001", holder));

        // Newcomer requests [ACC-001, ACC-002] — canonical sort: ACC-001 first
        assertThatThrownBy(() ->
                entityLockManager.acquireLocks(
                        List.of(new EntityRef("ACCOUNT", "ACC-001"),
                                new EntityRef("ACCOUNT", "ACC-002")),
                        newcomer))
                .isInstanceOf(EntityLockConflictException.class)
                .hasMessageContaining("ACC-001");

        // TX rolled back → ACC-002 must not have been locked by newcomer
        assertThat(lockRepo.findById(new SagaEntityLock.EntityLockId("ACCOUNT", "ACC-002"))).isEmpty();
        // Original holder still owns ACC-001
        assertThat(lockRepo.findById(new SagaEntityLock.EntityLockId("ACCOUNT", "ACC-001"))
                .map(SagaEntityLock::getSagaId)).contains(holder);
    }

    @Test
    void acquireLocks_emptyList_noOp() {
        assertThatCode(() -> entityLockManager.acquireLocks(List.of(), UUID.randomUUID()))
                .doesNotThrowAnyException();
    }

    @Test
    void releaseLock_removesLockFromDb() {
        UUID sagaId = UUID.randomUUID();
        lockRepo.save(new SagaEntityLock("ACCOUNT", "ACC-999", sagaId));

        entityLockManager.releaseLock("ACCOUNT", "ACC-999", sagaId);

        assertThat(lockRepo.findById(new SagaEntityLock.EntityLockId("ACCOUNT", "ACC-999"))).isEmpty();
    }

    /**
     * Concurrent acquire on the same entity: exactly one saga wins, the other gets 409.
     */
    @Test
    void concurrentAcquire_sameEntity_exactlyOneSucceeds() throws Exception {
        UUID sagaA = UUID.randomUUID();
        UUID sagaB = UUID.randomUUID();
        EntityRef entity = new EntityRef("ACCOUNT", "SHARED-001");

        CyclicBarrier   barrier       = new CyclicBarrier(2);
        AtomicInteger   successCount  = new AtomicInteger(0);
        AtomicInteger   conflictCount = new AtomicInteger(0);
        ExecutorService exec          = Executors.newFixedThreadPool(2);

        Future<?> futA = exec.submit(() -> {
            try { barrier.await(); } catch (Exception ignored) {}
            try {
                entityLockManager.acquireLocks(List.of(entity), sagaA);
                successCount.incrementAndGet();
            } catch (EntityLockConflictException e) {
                conflictCount.incrementAndGet();
            }
        });
        Future<?> futB = exec.submit(() -> {
            try { barrier.await(); } catch (Exception ignored) {}
            try {
                entityLockManager.acquireLocks(List.of(entity), sagaB);
                successCount.incrementAndGet();
            } catch (EntityLockConflictException e) {
                conflictCount.incrementAndGet();
            }
        });

        futA.get(5, TimeUnit.SECONDS);
        futB.get(5, TimeUnit.SECONDS);
        exec.shutdown();

        assertThat(successCount.get()).isEqualTo(1);
        assertThat(conflictCount.get()).isEqualTo(1);
    }

    /**
     * Deadlock-prevention via canonical sort.
     *
     * Saga A declares [ACCOUNT/BBB, ACCOUNT/AAA].
     * Saga B declares [ACCOUNT/AAA, ACCOUNT/BBB].
     * Without canonical sort both could acquire the first lock and deadlock waiting for the second.
     * With canonical sort both sort to [AAA, BBB] — they race on AAA, one wins immediately,
     * the other fails fast. Both futures complete within the timeout: no deadlock.
     */
    @Test
    void deadlockPrevention_canonicalSortCompletesWithoutDeadlock() throws Exception {
        UUID sagaA = UUID.randomUUID();
        UUID sagaB = UUID.randomUUID();

        List<EntityRef> sagaAEntities = List.of(
                new EntityRef("ACCOUNT", "BBB"),
                new EntityRef("ACCOUNT", "AAA"));
        List<EntityRef> sagaBEntities = List.of(
                new EntityRef("ACCOUNT", "AAA"),
                new EntityRef("ACCOUNT", "BBB"));

        CyclicBarrier   barrier       = new CyclicBarrier(2);
        AtomicInteger   completed     = new AtomicInteger(0);
        ExecutorService exec          = Executors.newFixedThreadPool(2);

        Future<?> futA = exec.submit(() -> {
            try { barrier.await(); } catch (Exception ignored) {}
            try {
                entityLockManager.acquireLocks(sagaAEntities, sagaA);
                entityLockManager.releaseAllLocks(sagaAEntities, sagaA);
            } catch (EntityLockConflictException ignored) {}
            completed.incrementAndGet();
        });
        Future<?> futB = exec.submit(() -> {
            try { barrier.await(); } catch (Exception ignored) {}
            try {
                entityLockManager.acquireLocks(sagaBEntities, sagaB);
                entityLockManager.releaseAllLocks(sagaBEntities, sagaB);
            } catch (EntityLockConflictException ignored) {}
            completed.incrementAndGet();
        });

        // Both futures must complete within 5 s — a deadlock would time out here
        futA.get(5, TimeUnit.SECONDS);
        futB.get(5, TimeUnit.SECONDS);
        exec.shutdown();

        assertThat(completed.get()).isEqualTo(2);
    }
}
