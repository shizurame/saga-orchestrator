package com.saga.orchestrator.web;

import com.saga.orchestrator.definition.StepExecutionStatus;

import java.time.Instant;
import java.util.UUID;

public record StepExecutionSummary(
        UUID    id,
        int     stepIndex,
        int     attemptNumber,
        String  idempotencyKey,
        StepExecutionStatus status,
        Instant startedAt,
        Instant completedAt
) {}
