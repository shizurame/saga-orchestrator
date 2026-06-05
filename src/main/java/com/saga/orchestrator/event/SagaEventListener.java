package com.saga.orchestrator.event;

public interface SagaEventListener {
    void onEvent(SagaExecutionEvent event);
}
