package com.saga.orchestrator.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.saga.orchestrator.definition.SagaStatus;
import com.saga.orchestrator.infrastructure.IdempotencyKeyGenerator;
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
import java.util.List;
import java.util.Map;
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
    @Autowired IdempotencyKeyGenerator keyGenerator;
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

    /**
     * Crash mid-step (not at a step boundary): a saga_step_executions row for `crashStep`
     * is already IN_PROGRESS (intent persisted, result never was) when the recovery scanner
     * claims the saga. Before this fix, resuming would call persistIntent for a fresh
     * attempt 1, violate uq_step_attempt, and silently stop (see _prep/phase2_plan.md §3
     * Task 1). This verifies recovery instead reuses the existing row and its key.
     *
     * timeout_at is set far in the future so this is isolated from TimeoutScanner — only
     * OrphanedSagaScanService's resume path is exercised.
     */
    @ParameterizedTest(name = "crash mid-step {0}")
    @ValueSource(ints = {0, 1, 2, 3, 4})
    void crashMidStep_recoveryReusesExistingKey(int crashStep) throws Exception {
        UUID sagaId = UUID.randomUUID();
        String sourceAccountId = "mid-crash-acc-" + crashStep + "-" + sagaId.toString().substring(0, 8);

        TransferContext ctx = preSeedContext(crashStep, sourceAccountId);
        String contextJson = objectMapper.writeValueAsString(ctx);

        jdbcTemplate.update("""
            INSERT INTO saga_instance (id, saga_type, status, current_step, context, version,
                                       lock_owner, heartbeat_at, lease_expires_at, fencing_token,
                                       created_at, updated_at)
            VALUES (?, 'fund-transfer', 'EXECUTING', ?, ?::jsonb, 1,
                    'dead-node-mid-crash-test', ?, ?, 1,
                    NOW(), NOW())
            """,
                sagaId, crashStep, contextJson,
                Timestamp.from(Instant.now().minus(1, ChronoUnit.HOURS)),
                Timestamp.from(Instant.now().minus(1, ChronoUnit.HOURS)));

        jdbcTemplate.update("""
            INSERT INTO saga_entity_locks (entity_type, entity_id, saga_id, acquired_at)
            VALUES ('account', ?, ?, NOW())
            """, sourceAccountId, sagaId);

        String preCrashKey = keyGenerator.forwardKey(sagaId, crashStep, 1);
        jdbcTemplate.update("""
            INSERT INTO saga_step_executions (id, saga_id, step_index, attempt_number,
                                               idempotency_key, status, timeout_at, started_at)
            VALUES (?, ?, ?, 1, ?, 'IN_PROGRESS', ?, NOW())
            """,
                UUID.randomUUID(), sagaId, crashStep, preCrashKey,
                Timestamp.from(Instant.now().plusSeconds(60)));

        orphanedSagaScanService.scan();

        SagaInstance recovered = sagaRepo.findById(sagaId).orElseThrow();
        assertThat(recovered.getStatus())
                .as("saga resumed mid-step %d should reach COMPLETED", crashStep)
                .isEqualTo(SagaStatus.COMPLETED);

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT attempt_number, idempotency_key, status FROM saga_step_executions " +
                "WHERE saga_id = ? AND step_index = ?", sagaId, crashStep);
        assertThat(rows)
                .as("resume must reuse the existing row, not insert a second attempt")
                .hasSize(1);
        assertThat(rows.get(0).get("idempotency_key"))
                .as("resume must reuse the pre-crash idempotency key, not a new one")
                .isEqualTo(preCrashKey);
        assertThat(rows.get(0).get("status")).isEqualTo("COMPLETED");

        Integer lockCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM saga_entity_locks WHERE saga_id = ?",
                Integer.class, sagaId);
        assertThat(lockCount).as("entity locks must be released on COMPLETED").isEqualTo(0);
    }

    /**
     * Case (a): crash after an attempt FAILED with retries still remaining. Before the fix,
     * resume always restarted at attempt 1 regardless of existing rows, so it collided with
     * the already-FAILED attempt-1 row via uq_step_attempt and silently stalled — exactly the
     * same shape of bug as the mid-step IN_PROGRESS case, just for a FAILED row instead.
     *
     * Only parameterized over steps that actually have retries (0, 1, 3, 4) — step 2
     * (debit-account) has maxAttempts=1, so "retries remaining after attempt 1 fails" cannot
     * happen for it; that step's analogous "final attempt" case is covered by case (b) below.
     */
    @ParameterizedTest(name = "crash after attempt 1 FAILED, retries remaining, step {0}")
    @ValueSource(ints = {0, 1, 3, 4})
    void crashAfterFailedAttempt_retriesRemaining_resumesAtNextAttempt(int crashStep) throws Exception {
        UUID sagaId = UUID.randomUUID();
        String sourceAccountId = "retry-resume-acc-" + crashStep + "-" + sagaId.toString().substring(0, 8);

        TransferContext ctx = preSeedContext(crashStep, sourceAccountId);
        String contextJson = objectMapper.writeValueAsString(ctx);

        jdbcTemplate.update("""
            INSERT INTO saga_instance (id, saga_type, status, current_step, context, version,
                                       lock_owner, heartbeat_at, lease_expires_at, fencing_token,
                                       created_at, updated_at)
            VALUES (?, 'fund-transfer', 'EXECUTING', ?, ?::jsonb, 1,
                    'dead-node-retry-resume', ?, ?, 1, NOW(), NOW())
            """,
                sagaId, crashStep, contextJson,
                Timestamp.from(Instant.now().minus(1, ChronoUnit.HOURS)),
                Timestamp.from(Instant.now().minus(1, ChronoUnit.HOURS)));

        jdbcTemplate.update("""
            INSERT INTO saga_entity_locks (entity_type, entity_id, saga_id, acquired_at)
            VALUES ('account', ?, ?, NOW())
            """, sourceAccountId, sagaId);

        // Attempt 1 already FAILED and committed; the crash happened before attempt 2
        // could even be tried.
        String attempt1Key = keyGenerator.forwardKey(sagaId, crashStep, 1);
        jdbcTemplate.update("""
            INSERT INTO saga_step_executions (id, saga_id, step_index, attempt_number,
                                               idempotency_key, status, timeout_at, started_at, completed_at)
            VALUES (?, ?, ?, 1, ?, 'FAILED', ?, NOW(), NOW())
            """,
                UUID.randomUUID(), sagaId, crashStep, attempt1Key,
                Timestamp.from(Instant.now().plusSeconds(30)));

        orphanedSagaScanService.scan();

        SagaInstance recovered = sagaRepo.findById(sagaId).orElseThrow();
        assertThat(recovered.getStatus())
                .as("saga resumed after a FAILED attempt 1 (retries remaining) at step %d should reach COMPLETED", crashStep)
                .isEqualTo(SagaStatus.COMPLETED);

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT attempt_number, status FROM saga_step_executions " +
                "WHERE saga_id = ? AND step_index = ? ORDER BY attempt_number", sagaId, crashStep);
        assertThat(rows).as("attempt 1 FAILED, attempt 2 should have been tried and completed").hasSize(2);
        assertThat(rows.get(0).get("attempt_number")).isEqualTo(1);
        assertThat(rows.get(0).get("status")).isEqualTo("FAILED");
        assertThat(rows.get(1).get("attempt_number")).isEqualTo(2);
        assertThat(rows.get(1).get("status")).isEqualTo("COMPLETED");
    }

    /**
     * Case (b): crash after the step's FINAL allowed attempt FAILED, before the
     * compensate-or-fail decision was ever written. Resume must not try a new attempt — it
     * must recognize the step has no attempts left and make the (deterministic) decision now:
     * step 0 has no prior COMPENSATABLE steps → FAILED; every other step has step 0
     * (COMPENSATABLE) before it → COMPENSATING → COMPENSATED.
     */
    @ParameterizedTest(name = "crash after final attempt FAILED, step {0}")
    @ValueSource(ints = {0, 1, 2, 3, 4})
    void crashAfterFinalAttemptFailed_beforeCompensationDecision_reachesCorrectTerminal(int crashStep) throws Exception {
        int maxAttempts = switch (crashStep) {
            case 2 -> 1; // debit-account: RetryPolicy.noRetry()
            default -> 3; // all other showcase steps: RetryPolicy.exponential(3, ...)
        };
        UUID sagaId = UUID.randomUUID();
        String sourceAccountId = "final-fail-acc-" + crashStep + "-" + sagaId.toString().substring(0, 8);

        TransferContext ctx = preSeedContext(crashStep, sourceAccountId);
        String contextJson = objectMapper.writeValueAsString(ctx);

        jdbcTemplate.update("""
            INSERT INTO saga_instance (id, saga_type, status, current_step, context, version,
                                       lock_owner, heartbeat_at, lease_expires_at, fencing_token,
                                       created_at, updated_at)
            VALUES (?, 'fund-transfer', 'EXECUTING', ?, ?::jsonb, 1,
                    'dead-node-final-fail', ?, ?, 1, NOW(), NOW())
            """,
                sagaId, crashStep, contextJson,
                Timestamp.from(Instant.now().minus(1, ChronoUnit.HOURS)),
                Timestamp.from(Instant.now().minus(1, ChronoUnit.HOURS)));

        jdbcTemplate.update("""
            INSERT INTO saga_entity_locks (entity_type, entity_id, saga_id, acquired_at)
            VALUES ('account', ?, ?, NOW())
            """, sourceAccountId, sagaId);

        // ALL attempts 1..maxAttempts already FAILED and committed — a real exhaustion, not
        // just the last one in isolation (seeding only the final attempt would let resume's
        // "restart at 1" quietly succeed on a fresh, never-before-seen attempt 1, masking the
        // actual bug for any maxAttempts > 1). The crash happened before the compensate-or-fail
        // decision following the last of these could be written.
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            String attemptKey = keyGenerator.forwardKey(sagaId, crashStep, attempt);
            jdbcTemplate.update("""
                INSERT INTO saga_step_executions (id, saga_id, step_index, attempt_number,
                                                   idempotency_key, status, timeout_at, started_at, completed_at)
                VALUES (?, ?, ?, ?, ?, 'FAILED', ?, NOW(), NOW())
                """,
                    UUID.randomUUID(), sagaId, crashStep, attempt, attemptKey,
                    Timestamp.from(Instant.now().plusSeconds(30)));
        }

        orphanedSagaScanService.scan();

        SagaInstance recovered = sagaRepo.findById(sagaId).orElseThrow();
        SagaStatus expected = (crashStep == 0) ? SagaStatus.FAILED : SagaStatus.COMPENSATED;
        assertThat(recovered.getStatus())
                .as("saga resumed after step %d's final attempt FAILED should reach %s", crashStep, expected)
                .isEqualTo(expected);

        // No new attempt was ever inserted for the step itself — exactly the maxAttempts FAILED rows.
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT attempt_number, status FROM saga_step_executions " +
                "WHERE saga_id = ? AND step_index = ? ORDER BY attempt_number", sagaId, crashStep);
        assertThat(rows).as("resume must not try a new attempt once retries are exhausted").hasSize(maxAttempts);
        rows.forEach(row -> assertThat(row.get("status")).isEqualTo("FAILED"));

        Integer lockCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM saga_entity_locks WHERE saga_id = ?",
                Integer.class, sagaId);
        assertThat(lockCount).as("entity locks must be released on a terminal transition").isEqualTo(0);
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
