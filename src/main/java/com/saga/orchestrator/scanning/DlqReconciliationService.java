package com.saga.orchestrator.scanning;

import com.saga.orchestrator.definition.SagaStatus;
import com.saga.orchestrator.persistence.entity.SagaInstance;
import com.saga.orchestrator.persistence.repository.SagaInstanceRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Scans for DEAD_LETTER sagas and emits structured alerts + metrics.
 *
 * DEAD_LETTER is the terminal state for sagas whose compensation retries were exhausted.
 * This job does NOT change state — it monitors and alerts so on-call can investigate.
 * In a real system: page, open a ticket, or push to a real DLQ topic.
 */
@Component
public class DlqReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(DlqReconciliationService.class);

    private final SagaInstanceRepository sagaRepo;
    private final MeterRegistry          meterRegistry;

    public DlqReconciliationService(SagaInstanceRepository sagaRepo, MeterRegistry meterRegistry) {
        this.sagaRepo      = sagaRepo;
        this.meterRegistry = meterRegistry;
    }

    public void reconcile() {
        List<SagaInstance> deadLetterSagas = sagaRepo.findByStatus(SagaStatus.DEAD_LETTER);

        for (SagaInstance saga : deadLetterSagas) {
            log.warn("DEAD_LETTER saga requires manual intervention: sagaId={} sagaType={} updatedAt={}",
                    saga.getId(), saga.getSagaType(), saga.getUpdatedAt());

            Counter.builder("saga_dead_letter_alerts_total")
                    .tag("saga_type", saga.getSagaType())
                    .description("Number of DEAD_LETTER sagas detected by the DLQ reconciliation job")
                    .register(meterRegistry)
                    .increment();
        }

        if (!deadLetterSagas.isEmpty()) {
            log.warn("DLQ reconciliation found {} saga(s) in DEAD_LETTER state", deadLetterSagas.size());
        }
    }
}
