package com.saga.orchestrator.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.saga.orchestrator.definition.SagaStatus;
import com.saga.orchestrator.persistence.entity.SagaInstance;
import com.saga.orchestrator.persistence.repository.SagaInstanceRepository;
import com.saga.orchestrator.scanning.OrphanedSagaScanService;
import com.saga.orchestrator.showcase.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Crash-at-each-step recovery test.
 *
 * For each step N (0..4): sets up a saga as if execution had progressed to step N
 * and the node crashed (expired lease, lock_owner="dead-node"). The recovery scanner
 * claims the saga and resumes from step N. Verifies the saga always reaches COMPLETED.
 *
 * DB setup approach (preferred over threading tricks): we directly construct the saga
 * state the crash would have left. This isolates the recovery path from the forward
 * execution path, making the test deterministic and fast.
 *
 * Entity locks are inserted so releaseLocksAndTransition has something to delete
 * on terminal transition (otherwise the saga hangs in COMPENSATING on empty lock table).
 */
class CrashAtEachStepTest extends AbstractIntegrationTest {

    @Autowired OrphanedSagaScanService orphanedSagaScanService;
    @Autowired SagaInstanceRepository  sagaRepo;
    @Autowired JdbcTemplate            jdbcTemplate;
    @Autowired ObjectMapper            objectMapper;
    @Autowired CreditCheckService      creditCheckService;
    @Autowired FundReservationService  fundReservationService;
    @Autowired DebitService            debitService;
    @Autowired CreditService           creditService;
    @Autowired NotificationService     notificationService;

    @BeforeEach
    void resetParticipants() {
        creditCheckService.reset();
        fundReservationService.reset();
        debitService.reset(); // asyncMode=false by default → debit returns Success synchronously
        creditService.reset();
        notificationService.reset();
    }

    @ParameterizedTest(name = "crash at step {0}")
    @ValueSource(ints = {0, 1, 2, 3, 4})
    void crashAtStep_recoveryResumesSagaToCompleted(int crashStep) throws Exception {
        UUID sagaId = UUID.randomUUID();
        String sourceAccountId = "crash-acc-" + crashStep + "-" + sagaId.toString().substring(0, 8);

        // Build a context that reflects whatever prior steps would have set
        TransferContext ctx = preSeedContext(crashStep, sourceAccountId);
        String contextJson = objectMapper.writeValueAsString(ctx);

        // Insert the saga as EXECUTING at crashStep with an expired lease on a dead node
        jdbcTemplate.update("""
            INSERT INTO saga_instance (id, saga_type, status, current_step, context, version,
                                       lock_owner, heartbeat_at, lease_expires_at, fencing_token,
                                       created_at, updated_at)
            VALUES (?, 'fund-transfer', 'EXECUTING', ?, ?::jsonb, 1,
                    'dead-node-crash-test', ?, ?, 1,
                    NOW(), NOW())
            """,
                sagaId, crashStep, contextJson,
                Timestamp.from(Instant.now().minus(1, ChronoUnit.HOURS)),
                Timestamp.from(Instant.now().minus(1, ChronoUnit.HOURS)));

        // Insert entity lock (scanner's recovery won't touch it; terminal transition deletes it)
        jdbcTemplate.update("""
            INSERT INTO saga_entity_locks (entity_type, entity_id, saga_id, acquired_at)
            VALUES ('account', ?, ?, NOW())
            """, sourceAccountId, sagaId);

        // Run the recovery scanner — it steals the stale lease and resumes the saga
        orphanedSagaScanService.scan();

        SagaInstance recovered = sagaRepo.findById(sagaId).orElseThrow();
        assertThat(recovered.getStatus())
                .as("saga resumed from step %d should reach COMPLETED", crashStep)
                .isEqualTo(SagaStatus.COMPLETED);

        // Entity lock must be cleaned up after terminal transition
        Integer lockCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM saga_entity_locks WHERE saga_id = ?",
                Integer.class, sagaId);
        assertThat(lockCount).as("entity locks must be released on COMPLETED").isEqualTo(0);
    }

    private TransferContext preSeedContext(int crashStep, String sourceAccountId) {
        TransferContext ctx = new TransferContext("cust-crash", sourceAccountId, "acc-dst-crash",
                new BigDecimal("750"));
        // Pre-populate fields that prior steps would have written to the context
        if (crashStep >= 2) ctx.setReservationId("rsv-preseeded");
        if (crashStep >= 3) ctx.setAuthorizationId("auth-preseeded");
        if (crashStep >= 4) ctx.setTransferId("txn-preseeded");
        return ctx;
    }
}
