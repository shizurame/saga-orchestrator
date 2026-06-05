package com.saga.orchestrator.web;

import com.saga.orchestrator.definition.SagaStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record SagaResponse(
        UUID     sagaId,
        String   sagaType,
        SagaStatus status,
        int      currentStep,
        Instant  createdAt,
        Instant  updatedAt,
        List<StepExecutionSummary> steps
) {}
