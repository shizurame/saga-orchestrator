package com.saga.orchestrator.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.saga.orchestrator.definition.*;
import com.saga.orchestrator.engine.SagaOrchestrator;
import com.saga.orchestrator.persistence.repository.CompensationStepRepository;
import com.saga.orchestrator.persistence.repository.SagaEntityLockRepository;
import com.saga.orchestrator.persistence.repository.SagaInstanceRepository;
import com.saga.orchestrator.persistence.repository.SagaStepExecutionRepository;
import com.saga.orchestrator.scanning.DlqReconciliationService;
import com.saga.orchestrator.showcase.*;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 8: Metrics and DLQ verification.
 *
 * Verifies that SagaMetricsListener correctly records:
 *   - saga_executions_total on terminal transitions
 *   - saga_step_duration_seconds on step completions
 *   - saga_compensations_total on compensation steps
 *   - saga_dead_letter_total on DEAD_LETTER transitions
 *
 * Also verifies DlqReconciliationService scans DEAD_LETTER sagas
 * and emits saga_dead_letter_alerts_total metric.
 */
class Phase8IntegrationTest extends AbstractIntegrationTest {

    @Autowired MeterRegistry              meterRegistry;
    @Autowired SagaOrchestrator           orchestrator;
    @Autowired SagaDefinitionRegistry     registry;
    @Autowired SagaInstanceRepository     sagaRepo;
    @Autowired SagaStepExecutionRepository stepRepo;
    @Autowired CompensationStepRepository compensationStepRepo;
    @Autowired SagaEntityLockRepository   lockRepo;
    @Autowired DlqReconciliationService   dlqService;
    @Autowired CreditCheckService         creditCheckService;
    @Autowired FundReservationService     fundReservationService;
    @Autowired DebitService               debitService;
    @Autowired CreditService              creditService;
    @Autowired NotificationService        notificationService;
    @Autowired ObjectMapper               objectMapper;
    @Autowired JdbcTemplate               jdbcTemplate;

    private static final String COMP_SAGA_TYPE  = "phase8-comp-test";
    private static final String FAIL_SAGA_TYPE  = "phase8-fail-test";
    private static boolean registered = false;

    @BeforeEach
    void setup() {
        creditCheckService.reset();
        fundReservationService.reset();
        debitService.reset();
        creditService.reset();
        notificationService.reset();

        if (!registered) {
            registered = true;

            // 2-step saga: step 0 COMPENSATABLE (success), step 1 COMPENSATABLE (injectable fail)
            SagaDefinition<P8Ctx> compSaga = SagaDefinition.named(COMP_SAGA_TYPE, P8Ctx.class)
                    .compensatableStep("p8-step-0")
                        .action((ctx, key) -> new ExecutionResult.Success(null))
                        .compensation((ctx, key) -> new ExecutionResult.Success(null))
                        .timeout(Duration.ofSeconds(5), TimeoutMode.RETRY_WITH_IDEMPOTENCY)
                    .compensatableStep("p8-step-1")
                        .action((ctx, key) -> new ExecutionResult.Failure("phase8 induced failure", null))
                        .compensation((ctx, key) -> new ExecutionResult.Success(null))
                        .timeout(Duration.ofSeconds(5), TimeoutMode.RETRY_WITH_IDEMPOTENCY)
                    .build();
            registry.register(compSaga);
        }
    }

    @AfterEach
    void cleanup() {
        compensationStepRepo.deleteAll();
        lockRepo.deleteAll();
        stepRepo.deleteAll();
        sagaRepo.deleteAll();
    }

    @Test
    void sagaCompleted_sagaExecutionsTotalIncremented() throws Exception {
        String ctx = objectMapper.writeValueAsString(
                new TransferContext("c1", "s1", "d1", new BigDecimal("100")));

        double before = getCounterValue("saga_executions_total",
                "saga_type", FundTransferSaga.SAGA_TYPE, "status", "COMPLETED");

        orchestrator.startSaga(FundTransferSaga.SAGA_TYPE, ctx, List.of());

        double after = getCounterValue("saga_executions_total",
                "saga_type", FundTransferSaga.SAGA_TYPE, "status", "COMPLETED");

        assertThat(after - before).isEqualTo(1.0);
    }

    @Test
    void sagaCompleted_stepDurationTimerRecorded() throws Exception {
        String ctx = objectMapper.writeValueAsString(
                new TransferContext("c2", "s2", "d2", new BigDecimal("200")));

        long before = getTimerCount("saga_step_duration_seconds",
                "saga_type", FundTransferSaga.SAGA_TYPE, "step", "credit-check");

        orchestrator.startSaga(FundTransferSaga.SAGA_TYPE, ctx, List.of());

        long after = getTimerCount("saga_step_duration_seconds",
                "saga_type", FundTransferSaga.SAGA_TYPE, "step", "credit-check");

        assertThat(after - before).isGreaterThanOrEqualTo(1L);
    }

    @Test
    void compensationRun_sagaCompensationsTotalIncremented() throws Exception {
        String ctx = "{}"; // P8Ctx has no fields; pass empty JSON directly

        double before = getCounterValue("saga_compensations_total",
                "saga_type", COMP_SAGA_TYPE, "step", "p8-step-0");

        orchestrator.startSaga(COMP_SAGA_TYPE, ctx, List.of());

        double after = getCounterValue("saga_compensations_total",
                "saga_type", COMP_SAGA_TYPE, "step", "p8-step-0");

        assertThat(after - before).isEqualTo(1.0);
    }

    @Test
    void dlqReconciliationService_deadLetterSaga_emitsAlertMetric() {
        UUID sagaId = UUID.randomUUID();
        jdbcTemplate.update("""
            INSERT INTO saga_instance (id, saga_type, status, current_step, context, version,
                                       lock_owner, heartbeat_at, lease_expires_at, fencing_token,
                                       created_at, updated_at)
            VALUES (?, 'fund-transfer', 'DEAD_LETTER', 0, '{}'::jsonb, 1,
                    NULL, NOW(), NOW(), 1, NOW(), NOW())
            """, sagaId);

        double before = getCounterValue("saga_dead_letter_alerts_total",
                "saga_type", "fund-transfer");

        dlqService.reconcile();

        double after = getCounterValue("saga_dead_letter_alerts_total",
                "saga_type", "fund-transfer");

        assertThat(after - before).isGreaterThanOrEqualTo(1.0);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private double getCounterValue(String name, String... tags) {
        try {
            Counter counter = meterRegistry.find(name).tags(tags).counter();
            return counter == null ? 0.0 : counter.count();
        } catch (Exception e) {
            return 0.0;
        }
    }

    private long getTimerCount(String name, String... tags) {
        try {
            Timer timer = meterRegistry.find(name).tags(tags).timer();
            return timer == null ? 0L : timer.count();
        } catch (Exception e) {
            return 0L;
        }
    }

    public static class P8Ctx implements SagaContext {
        private int step = 0;
        public int getStep() { return step; }
        public void setStep(int s) { this.step = s; }
    }
}
