package com.saga.orchestrator.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.saga.orchestrator.definition.EntityLockConflictException;
import com.saga.orchestrator.definition.EntityRef;
import com.saga.orchestrator.definition.SagaStatus;
import com.saga.orchestrator.engine.SagaOrchestrator;
import com.saga.orchestrator.persistence.repository.SagaInstanceRepository;
import com.saga.orchestrator.showcase.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Demonstrates entity-level saga serialization:
 * two sagas on the same sourceAccountId cannot run concurrently.
 *
 * The entity lock (account:sourceAccountId) is held from initialization through
 * the terminal state transition. Only one saga at a time can operate on that account.
 *
 * Concurrency model:
 *   - Saga A parks in WAITING_FOR_CALLBACK (debitService.asyncMode=true) holding the lock.
 *   - Saga B tries to start on the same account → EntityLockConflictException (409).
 *   - Callback delivered for Saga A → completes → lock released.
 *   - Saga B retried → now acquires the lock → completes.
 */
class ConcurrentSagaSameAccountTest extends AbstractIntegrationTest {

    @Autowired SagaOrchestrator       orchestrator;
    @Autowired SagaInstanceRepository sagaRepo;
    @Autowired ObjectMapper           objectMapper;
    @Autowired CreditCheckService     creditCheckService;
    @Autowired FundReservationService fundReservationService;
    @Autowired DebitService           debitService;
    @Autowired CreditService          creditService;
    @Autowired NotificationService    notificationService;

    private static final String SHARED_ACCOUNT = "acc-shared-concurrent";

    @BeforeEach
    void resetParticipants() {
        creditCheckService.reset();
        fundReservationService.reset();
        debitService.reset();
        creditService.reset();
        notificationService.reset();
    }

    @Test
    void concurrentSagas_sameLock_serialized() throws Exception {
        String contextJson = objectMapper.writeValueAsString(
                new TransferContext("cust-concurrent", SHARED_ACCOUNT, "acc-dst-concurrent",
                        new BigDecimal("300")));
        List<EntityRef> locks = List.of(new EntityRef(FundTransferSaga.ENTITY_TYPE_ACCOUNT, SHARED_ACCOUNT));

        // Saga A: debit step returns TimedOut → saga parks holding the entity lock
        debitService.setAsyncMode(true);
        UUID sagaAId = orchestrator.startSaga(FundTransferSaga.SAGA_TYPE, contextJson, locks);
        assertThat(sagaRepo.findById(sagaAId).orElseThrow().getStatus())
                .isEqualTo(SagaStatus.WAITING_FOR_CALLBACK);

        // Saga B: same entity lock → EntityLockConflictException
        AtomicReference<Exception> sagaBException = new AtomicReference<>();
        ExecutorService exec = Executors.newSingleThreadExecutor();
        Future<?> sagaB = exec.submit(() -> {
            try {
                orchestrator.startSaga(FundTransferSaga.SAGA_TYPE, contextJson, locks);
            } catch (EntityLockConflictException e) {
                sagaBException.set(e);
            }
        });
        sagaB.get(5, TimeUnit.SECONDS);
        exec.shutdown();

        assertThat(sagaBException.get())
                .as("second saga on same account must get EntityLockConflictException")
                .isInstanceOf(EntityLockConflictException.class);

        // Deliver callback for Saga A → completes, releases lock
        debitService.setAsyncMode(false);
        orchestrator.receiveCallback(sagaAId, 2, true, null);
        assertThat(sagaRepo.findById(sagaAId).orElseThrow().getStatus())
                .isEqualTo(SagaStatus.COMPLETED);

        // Saga B retried — lock is now free
        UUID sagaBRetryId = orchestrator.startSaga(FundTransferSaga.SAGA_TYPE, contextJson, locks);
        assertThat(sagaRepo.findById(sagaBRetryId).orElseThrow().getStatus())
                .isEqualTo(SagaStatus.COMPLETED);
    }
}
