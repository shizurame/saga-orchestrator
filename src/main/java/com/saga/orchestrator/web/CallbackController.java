package com.saga.orchestrator.web;

import com.saga.orchestrator.definition.SagaStatus;
import com.saga.orchestrator.engine.SagaOrchestrator;
import com.saga.orchestrator.persistence.entity.SagaInstance;
import com.saga.orchestrator.persistence.repository.SagaInstanceRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

import static org.springframework.http.HttpStatus.*;

/**
 * REST endpoint for external systems to deliver WAIT_FOR_CALLBACK results.
 *
 * When a saga step uses TimeoutMode.WAIT_FOR_CALLBACK, the orchestrator parks the saga
 * in WAITING_FOR_CALLBACK status and the ForwardExecutionEngine stops. The external system
 * (e.g. a banking partner) processes the request asynchronously and calls this endpoint
 * when the result is ready.
 *
 * POST /sagas/{sagaId}/callback/{stepIndex}?success=true|false
 *
 * Validates:
 *   - Saga exists
 *   - Saga is in WAITING_FOR_CALLBACK status
 *   - stepIndex matches saga.currentStep - 1 (the step that was waiting)
 */
@RestController
@RequestMapping("/sagas")
public class CallbackController {

    private final SagaOrchestrator       orchestrator;
    private final SagaInstanceRepository sagaRepo;

    public CallbackController(SagaOrchestrator orchestrator,
                              SagaInstanceRepository sagaRepo) {
        this.orchestrator = orchestrator;
        this.sagaRepo     = sagaRepo;
    }

    @PostMapping("/{sagaId}/callback/{stepIndex}")
    public ResponseEntity<Void> receiveCallback(
            @PathVariable UUID sagaId,
            @PathVariable int stepIndex,
            @RequestParam(defaultValue = "true") boolean success,
            @RequestBody(required = false) Object result) {

        SagaInstance saga = sagaRepo.findById(sagaId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Saga not found: " + sagaId));

        if (saga.getStatus() != SagaStatus.WAITING_FOR_CALLBACK) {
            throw new ResponseStatusException(CONFLICT,
                    "Saga is not waiting for callback; current status: " + saga.getStatus());
        }

        // currentStep was advanced by persistStepTimeout in ForwardExecutionEngine,
        // but actually NOT — the step timed out before completing, so currentStep was NOT incremented.
        // The step at saga.currentStep is the WAIT_FOR_CALLBACK step.
        if (saga.getCurrentStep() != stepIndex) {
            throw new ResponseStatusException(CONFLICT,
                    "Step index mismatch: saga is at step " + saga.getCurrentStep()
                    + " but callback delivered for step " + stepIndex);
        }

        orchestrator.receiveCallback(sagaId, stepIndex, success, result);
        return ResponseEntity.ok().build();
    }
}
