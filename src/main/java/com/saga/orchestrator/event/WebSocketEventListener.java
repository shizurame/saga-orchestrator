package com.saga.orchestrator.event;

import com.saga.orchestrator.definition.SagaStatus;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;

/**
 * Broadcasts saga state transitions to WebSocket subscribers in real time.
 *
 * Each SagaExecutionEvent is serialized to a simple JSON map and sent to
 * /topic/sagas/{sagaId}. Any client subscribed to that topic receives the
 * event immediately after it is persisted — the event is published from
 * within the REQUIRES_NEW TX commit, so the data is already durable when
 * the WebSocket message is dispatched.
 *
 * Clients: the demo page at /index.html uses SockJS + STOMP.js to subscribe
 * and render live progress. Production clients (mobile apps, dashboards) use
 * the same protocol.
 */
@Component
public class WebSocketEventListener implements SagaEventListener {

    private final SimpMessagingTemplate messagingTemplate;

    public WebSocketEventListener(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    @Override
    public void onEvent(SagaExecutionEvent event) {
        Map<String, Object> payload = Map.of(
                "sagaId",     event.sagaId().toString(),
                "sagaType",   event.sagaType(),
                "step",       event.stepName(),
                "fromStatus", event.fromStatus().name(),
                "toStatus",   event.toStatus().name(),
                "terminal",   event.toStatus().isTerminal(),
                "durationMs", event.durationMs(),
                "timestamp",  event.timestamp().toString()
        );

        messagingTemplate.convertAndSend("/topic/sagas/" + event.sagaId(), payload);
    }
}
