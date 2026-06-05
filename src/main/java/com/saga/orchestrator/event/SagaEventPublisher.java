package com.saga.orchestrator.event;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Synchronous event dispatcher. Each listener (metrics, WebSocket, audit) is invoked
 * in-process before the engine moves to the next step.
 */
@Component
public class SagaEventPublisher {

    private final List<SagaEventListener> listeners;

    public SagaEventPublisher(List<SagaEventListener> listeners) {
        this.listeners = listeners;
    }

    public void publish(SagaExecutionEvent event) {
        for (SagaEventListener listener : listeners) {
            listener.onEvent(event);
        }
    }
}
