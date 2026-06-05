package com.saga.orchestrator.event;

import com.saga.orchestrator.definition.SagaStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * Domain event published after every saga status transition.
 * Listeners (metrics, WebSocket, audit log) implement SagaEventListener.
 */
public record SagaExecutionEvent(
        UUID sagaId,
        String sagaType,
        String stepName,
        SagaStatus fromStatus,
        SagaStatus toStatus,
        Instant timestamp,
        long durationMs
) {}
