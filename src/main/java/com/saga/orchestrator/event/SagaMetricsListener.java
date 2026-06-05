package com.saga.orchestrator.event;

import com.saga.orchestrator.definition.SagaStatus;
import com.saga.orchestrator.persistence.repository.SagaInstanceRepository;
import io.micrometer.core.instrument.*;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * Translates SagaExecutionEvents into Micrometer metrics exposed at /actuator/prometheus.
 *
 * Metrics emitted:
 *   saga_executions_total{saga_type, status}        — counter per terminal transition
 *   saga_step_duration_seconds{saga_type, step}     — histogram for each forward step
 *   saga_compensations_total{saga_type, step}       — counter per compensation step completed
 *   saga_dead_letter_total{saga_type}               — counter when saga reaches DEAD_LETTER
 *   saga_in_flight{saga_type}                       — gauge: count of EXECUTING sagas in DB
 *
 * Compensation detection: persistCompensationSuccess publishes an event with
 * fromStatus=COMPENSATING, toStatus=COMPENSATING. All other same-status transitions
 * don't occur, so this is unambiguous.
 *
 * In-flight uses a DB-backed gauge (countByStatus) rather than event-driven increment/decrement
 * because SagaInitializer sets EXECUTING status without publishing an event, making
 * event-based tracking inaccurate. The DB gauge is always correct and self-healing.
 */
@Component
public class SagaMetricsListener implements SagaEventListener {

    private final MeterRegistry          registry;
    private final SagaInstanceRepository sagaRepo;

    public SagaMetricsListener(MeterRegistry registry, SagaInstanceRepository sagaRepo) {
        this.registry  = registry;
        this.sagaRepo  = sagaRepo;

        // Register in-flight gauge once at startup. Returns live DB count on each scrape.
        Gauge.builder("saga_in_flight", sagaRepo, r -> r.countByStatus(SagaStatus.EXECUTING))
                .description("Number of sagas currently executing (all types)")
                .register(registry);
    }

    @Override
    public void onEvent(SagaExecutionEvent event) {
        SagaStatus from = event.fromStatus();
        SagaStatus to   = event.toStatus();

        // ── Forward step completed (saga advanced but not yet terminal) ────────
        if (from == SagaStatus.EXECUTING && to == SagaStatus.EXECUTING && event.durationMs() > 0) {
            Timer.builder("saga_step_duration_seconds")
                    .tag("saga_type", event.sagaType())
                    .tag("step", event.stepName())
                    .description("Duration of each forward step execution")
                    .register(registry)
                    .record(event.durationMs(), TimeUnit.MILLISECONDS);
        }

        // ── Saga completed all forward steps ──────────────────────────────────
        if (from == SagaStatus.EXECUTING && to == SagaStatus.COMPLETED) {
            Timer.builder("saga_step_duration_seconds")
                    .tag("saga_type", event.sagaType())
                    .tag("step", event.stepName())
                    .description("Duration of each forward step execution")
                    .register(registry)
                    .record(event.durationMs(), TimeUnit.MILLISECONDS);
        }

        // ── Compensation step completed (COMPENSATING→COMPENSATING mid-saga) ──
        if (from == SagaStatus.COMPENSATING && to == SagaStatus.COMPENSATING) {
            Counter.builder("saga_compensations_total")
                    .tag("saga_type", event.sagaType())
                    .tag("step", event.stepName())
                    .description("Number of compensation steps successfully executed")
                    .register(registry)
                    .increment();
        }

        // ── Terminal transitions ───────────────────────────────────────────────
        if (to.isTerminal()) {
            Counter.builder("saga_executions_total")
                    .tag("saga_type", event.sagaType())
                    .tag("status", to.name())
                    .description("Total saga executions by terminal status")
                    .register(registry)
                    .increment();

            if (to == SagaStatus.DEAD_LETTER) {
                Counter.builder("saga_dead_letter_total")
                        .tag("saga_type", event.sagaType())
                        .description("Sagas that exhausted compensation retries and require manual intervention")
                        .register(registry)
                        .increment();
            }
        }
    }
}
