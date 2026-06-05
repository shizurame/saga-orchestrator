package com.saga.orchestrator.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.saga.orchestrator.definition.CompensationStatus;
import com.saga.orchestrator.definition.SagaStatus;
import com.saga.orchestrator.infrastructure.IdempotencyKeyGenerator;
import com.saga.orchestrator.persistence.entity.CompensationStep;
import com.saga.orchestrator.persistence.entity.SagaInstance;
import com.saga.orchestrator.persistence.repository.CompensationStepRepository;
import com.saga.orchestrator.persistence.repository.SagaInstanceRepository;
import com.saga.orchestrator.scanning.OrphanedSagaScanService;
import com.saga.orchestrator.showcase.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Crash during compensation: verifies that if the orchestrator crashes while
 * compensating step 1 (IN_PROGRESS), the recovery scanner resumes from step 1
 * and completes compensation of both step 1 and step 0.
 *
 * DB setup:
 *   - saga: COMPENSATING, expired lease
 *   - compensation steps: step 1 = IN_PROGRESS (crash between intent and result)
 *                         step 0 = PENDING
 *   - entity lock present (will be released on terminal transition)
 *
 * CompensationEngine loads PENDING + IN_PROGRESS steps ordered DESC step_index:
 * first retries step 1, then step 0. Both succeed → saga COMPENSATED.
 */
class CompensationUnderCrashTest extends AbstractIntegrationTest {

    @Autowired OrphanedSagaScanService  orphanedSagaScanService;
    @Autowired SagaInstanceRepository   sagaRepo;
    @Autowired CompensationStepRepository compStepRepo;
    @Autowired IdempotencyKeyGenerator  keyGenerator;
    @Autowired JdbcTemplate             jdbcTemplate;
    @Autowired ObjectMapper             objectMapper;
    @Autowired CreditCheckService       creditCheckService;
    @Autowired FundReservationService   fundReservationService;
    @Autowired DebitService             debitService;
    @Autowired CreditService            creditService;
    @Autowired NotificationService      notificationService;

    @BeforeEach
    void resetParticipants() {
        creditCheckService.reset();
        fundReservationService.reset();
        debitService.reset();
        creditService.reset();
        notificationService.reset();
    }

    @Test
    void crashDuringCompensation_recoveryCompensatesAllSteps() throws Exception {
        UUID sagaId = UUID.randomUUID();
        String sourceAccountId = "acc-comp-crash-" + sagaId.toString().substring(0, 8);

        TransferContext ctx = new TransferContext("cust-comp-crash", sourceAccountId,
                "acc-dst-comp", new BigDecimal("400"));
        ctx.setReservationId("rsv-preseeded-crash");
        String contextJson = objectMapper.writeValueAsString(ctx);

        // Insert saga in COMPENSATING status with expired lease on dead node
        jdbcTemplate.update("""
            INSERT INTO saga_instance (id, saga_type, status, current_step, context, version,
                                       lock_owner, heartbeat_at, lease_expires_at, fencing_token,
                                       created_at, updated_at)
            VALUES (?, 'fund-transfer', 'COMPENSATING', 2, ?::jsonb, 1,
                    'dead-node-comp-crash', ?, ?, 1, NOW(), NOW())
            """,
                sagaId, contextJson,
                Timestamp.from(Instant.now().minus(1, ChronoUnit.HOURS)),
                Timestamp.from(Instant.now().minus(1, ChronoUnit.HOURS)));

        // Compensation step 1 (reserve-funds) — IN_PROGRESS (crashed after intent, before result)
        String compKey1 = keyGenerator.compensationKey(sagaId, 1, 0);
        CompensationStep compStep1 = CompensationStep.pending(sagaId, 1, "reserve-funds", compKey1);
        compStep1.markInProgress(); // simulate: intent was written, result was not
        compStepRepo.save(compStep1);

        // Compensation step 0 (credit-check) — PENDING (not yet started)
        String compKey0 = keyGenerator.compensationKey(sagaId, 0, 0);
        CompensationStep compStep0 = CompensationStep.pending(sagaId, 0, "credit-check", compKey0);
        compStepRepo.save(compStep0);

        // Insert entity lock (must exist for releaseLocksAndTransition to clean up)
        jdbcTemplate.update("""
            INSERT INTO saga_entity_locks (entity_type, entity_id, saga_id, acquired_at)
            VALUES ('account', ?, ?, NOW())
            """, sourceAccountId, sagaId);

        // Run recovery scanner
        orphanedSagaScanService.scan();

        // Verify: saga COMPENSATED
        SagaInstance recovered = sagaRepo.findById(sagaId).orElseThrow();
        assertThat(recovered.getStatus()).isEqualTo(SagaStatus.COMPENSATED);

        // Verify: both compensation steps completed
        List<CompensationStep> compSteps = compStepRepo.findBySagaIdAndStatusOrderByStepIndexDesc(
                sagaId, CompensationStatus.COMPLETED);
        assertThat(compSteps).hasSize(2);
        assertThat(compSteps).extracting(CompensationStep::getStepIndex)
                .containsExactlyInAnyOrder(0, 1);

        // Entity lock released
        Integer lockCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM saga_entity_locks WHERE saga_id = ?",
                Integer.class, sagaId);
        assertThat(lockCount).isEqualTo(0);
    }
}
