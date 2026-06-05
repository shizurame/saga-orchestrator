package com.saga.orchestrator.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.saga.orchestrator.definition.EntityLockConflictException;
import com.saga.orchestrator.engine.SagaOrchestrator;
import com.saga.orchestrator.persistence.entity.SagaInstance;
import com.saga.orchestrator.persistence.entity.SagaStepExecution;
import com.saga.orchestrator.persistence.repository.SagaInstanceRepository;
import com.saga.orchestrator.persistence.repository.SagaStepExecutionRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * Public REST API for saga lifecycle operations.
 *
 * POST /sagas         — start a new saga; blocks until terminal state (COMPLETED, COMPENSATED, etc.)
 *                       or until parking (WAITING_FOR_CALLBACK).
 * GET  /sagas/{id}    — fetch current saga status + step execution history.
 *
 * The orchestrator is synchronous for bean-step sagas. WAIT_FOR_CALLBACK sagas return
 * immediately in WAITING_FOR_CALLBACK; the client polls GET /sagas/{id} or subscribes
 * to the WebSocket topic (Phase 8) for the final state.
 */
@RestController
@RequestMapping("/sagas")
public class SagaController {

    private final SagaOrchestrator            orchestrator;
    private final SagaInstanceRepository      sagaRepo;
    private final SagaStepExecutionRepository stepRepo;
    private final ObjectMapper                objectMapper;

    public SagaController(SagaOrchestrator orchestrator,
                          SagaInstanceRepository sagaRepo,
                          SagaStepExecutionRepository stepRepo,
                          ObjectMapper objectMapper) {
        this.orchestrator  = orchestrator;
        this.sagaRepo      = sagaRepo;
        this.stepRepo      = stepRepo;
        this.objectMapper  = objectMapper;
    }

    @PostMapping
    public ResponseEntity<SagaResponse> startSaga(@RequestBody StartSagaRequest request) {
        String contextJson;
        try {
            contextJson = objectMapper.writeValueAsString(request.context());
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid context JSON: " + e.getMessage());
        }

        UUID sagaId = orchestrator.startSaga(request.sagaType(), contextJson, request.lockedEntities());
        return ResponseEntity.ok(buildResponse(sagaId));
    }

    @ExceptionHandler(EntityLockConflictException.class)
    public ResponseEntity<Map<String, String>> handleLockConflict(EntityLockConflictException ex) {
        return ResponseEntity.status(CONFLICT).body(Map.of("error", ex.getMessage()));
    }

    @GetMapping("/{id}")
    public ResponseEntity<SagaResponse> getSaga(@PathVariable UUID id) {
        sagaRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Saga not found: " + id));
        return ResponseEntity.ok(buildResponse(id));
    }

    private SagaResponse buildResponse(UUID sagaId) {
        SagaInstance saga = sagaRepo.findById(sagaId).orElseThrow();
        List<SagaStepExecution> execs = stepRepo
                .findBySagaIdOrderByStepIndexAscAttemptNumberAsc(sagaId);

        List<StepExecutionSummary> stepSummaries = execs.stream()
                .map(e -> new StepExecutionSummary(
                        e.getId(),
                        e.getStepIndex(),
                        e.getAttemptNumber(),
                        e.getIdempotencyKey(),
                        e.getStatus(),
                        e.getStartedAt(),
                        e.getCompletedAt()))
                .toList();

        return new SagaResponse(
                saga.getId(),
                saga.getSagaType(),
                saga.getStatus(),
                saga.getCurrentStep(),
                saga.getCreatedAt(),
                saga.getUpdatedAt(),
                stepSummaries);
    }
}
