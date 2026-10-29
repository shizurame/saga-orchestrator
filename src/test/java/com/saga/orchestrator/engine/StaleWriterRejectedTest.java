package com.saga.orchestrator.engine;

import com.saga.orchestrator.definition.SagaStatus;
import com.saga.orchestrator.definition.StepExecutionStatus;
import com.saga.orchestrator.infrastructure.HeartbeatService;
import com.saga.orchestrator.infrastructure.IdempotencyKeyGenerator;
import com.saga.orchestrator.infrastructure.SagaLockManager;
import com.saga.orchestrator.integration.AbstractIntegrationTest;
import com.saga.orchestrator.persistence.entity.SagaInstance;
import com.saga.orchestrator.persistence.entity.SagaStepExecution;
import com.saga.orchestrator.persistence.repository.SagaInstanceRepository;
import com.saga.orchestrator.persistence.repository.SagaStepExecutionRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.OptionalLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Lives in engine/ (not integration/) specifically to reach the package-private
 * StepPersistenceService directly — this is a precise test of the fencing check itself,
 * not an end-to-end scenario (ForwardExecutionEngine's own catch-and-stop behavior around
 * FencedOutException is exercised indirectly by every other test that completes a saga).
 *
 * Simulates two nodes sharing one Spring context (as the review's own tip suggests):
 * "node A" and "node B" are modeled by what HeartbeatService is told to track, not by two
 * separate processes — the fencing check only cares about the token this node remembers
 * versus the token currently on the row, which is exactly what this isolates.
 */
class StaleWriterRejectedTest extends AbstractIntegrationTest {

    @Autowired StepPersistenceService persistence;
    @Autowired HeartbeatService heartbeatService;
    @Autowired SagaLockManager sagaLockManager;
    @Autowired SagaInstanceRepository sagaRepo;
    @Autowired SagaStepExecutionRepository stepRepo;
    @Autowired IdempotencyKeyGenerator keyGenerator;

    @AfterEach
    void cleanup() {
        stepRepo.deleteAll();
        sagaRepo.deleteAll();
    }

    @Test
    void staleNode_writeRejected_freshNode_writeSucceeds() {
        // Node A claims the saga — token 1 — and tracks it.
        SagaInstance saga = sagaRepo.save(SagaInstance.create("fund-transfer", "{}"));
        saga.setStatus(SagaStatus.EXECUTING);
        sagaRepo.save(saga);
        var sagaId = saga.getId();

        OptionalLong tokenA = sagaLockManager.claimSaga(sagaId);
        assertThat(tokenA).hasValue(1L);
        heartbeatService.track(sagaId, tokenA.getAsLong());

        // A persists intent for a step (persistIntent is not fenced — see StepPersistenceService
        // and the Phase 2 plan for why only result/transition writes are).
        String key = keyGenerator.forwardKey(sagaId, 0, 1);
        SagaStepExecution exec = persistence.persistIntent(sagaId, 0, 1, key, Instant.now().plusSeconds(30));

        // Node B steals the lease: force expiry, then claim again (same Spring bean, but this
        // models a different node — only HeartbeatService's tracking map distinguishes them).
        forceLeaseExpired(sagaId);
        OptionalLong tokenB = sagaLockManager.claimSaga(sagaId);
        assertThat(tokenB).hasValue(2L);
        // Node A's HeartbeatService entry is untouched here — it still (wrongly) believes 1.

        // A, now stale, tries to complete the step it started — must be rejected.
        assertThatThrownBy(() ->
                persistence.persistStepSuccess(sagaId, exec.getId(), "ignored", "{}", true, "solo-step"))
                .isInstanceOf(FencedOutException.class);

        // Rejected write changed nothing.
        SagaStepExecution stillInProgress = stepRepo.findById(exec.getId()).orElseThrow();
        assertThat(stillInProgress.getStatus()).isEqualTo(StepExecutionStatus.IN_PROGRESS);
        assertThat(sagaRepo.findById(sagaId).orElseThrow().getCurrentStep()).isEqualTo(0);

        // Node B now tracks its real token and retries the same write — succeeds.
        heartbeatService.track(sagaId, tokenB.getAsLong());
        persistence.persistStepSuccess(sagaId, exec.getId(), "ignored", "{}", true, "solo-step");

        SagaInstance completed = sagaRepo.findById(sagaId).orElseThrow();
        assertThat(completed.getStatus()).isEqualTo(SagaStatus.COMPLETED);
    }

    private void forceLeaseExpired(java.util.UUID sagaId) {
        SagaInstance saga = sagaRepo.findById(sagaId).orElseThrow();
        saga.setLeaseExpiresAt(Instant.now().minusSeconds(1));
        sagaRepo.save(saga);
    }
}
