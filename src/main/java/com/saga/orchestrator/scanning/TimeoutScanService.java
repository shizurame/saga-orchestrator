package com.saga.orchestrator.scanning;

import com.saga.orchestrator.engine.TimeoutHandler;
import com.saga.orchestrator.persistence.entity.SagaStepExecution;
import com.saga.orchestrator.persistence.repository.SagaInstanceRepository;
import com.saga.orchestrator.persistence.repository.SagaStepExecutionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Scans for IN_PROGRESS step executions that have exceeded their timeout_at deadline.
 *
 * Only RETRY_WITH_IDEMPOTENCY steps remain IN_PROGRESS past their deadline:
 *   - TREAT_AS_FAILURE steps are marked FAILED immediately in ForwardExecutionEngine.
 *   - WAIT_FOR_CALLBACK steps are marked TIMED_OUT and excluded from the IN_PROGRESS query.
 *
 * For each overdue step, delegates to TimeoutHandler which re-executes with the same
 * idempotency key so the participant can return a cached result.
 *
 * Called directly from TimeoutScanner (Quartz Job). The public scan() method is also
 * injectable for testing without Quartz overhead.
 */
@Component
public class TimeoutScanService {

    private static final Logger log = LoggerFactory.getLogger(TimeoutScanService.class);

    private final SagaStepExecutionRepository stepRepo;
    private final SagaInstanceRepository      sagaRepo;
    private final TimeoutHandler              timeoutHandler;

    public TimeoutScanService(SagaStepExecutionRepository stepRepo,
                              SagaInstanceRepository sagaRepo,
                              TimeoutHandler timeoutHandler) {
        this.stepRepo       = stepRepo;
        this.sagaRepo       = sagaRepo;
        this.timeoutHandler = timeoutHandler;
    }

    public void scan() {
        List<SagaStepExecution> timedOut = stepRepo.findTimedOutSteps(Instant.now());
        if (!timedOut.isEmpty()) {
            log.info("TimeoutScanner found {} overdue IN_PROGRESS step(s)", timedOut.size());
        }

        for (SagaStepExecution exec : timedOut) {
            sagaRepo.findById(exec.getSagaId()).ifPresent(saga -> {
                if (!saga.getStatus().isTerminal()) {
                    try {
                        timeoutHandler.handle(exec, saga);
                    } catch (Exception e) {
                        log.error("Error handling timeout for saga={} step={}",
                                exec.getSagaId(), exec.getStepIndex(), e);
                    }
                }
            });
        }
    }
}
