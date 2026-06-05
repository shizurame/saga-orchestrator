package com.saga.orchestrator.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.saga.orchestrator.definition.EntityRef;
import com.saga.orchestrator.persistence.repository.CompensationStepRepository;
import com.saga.orchestrator.persistence.repository.SagaEntityLockRepository;
import com.saga.orchestrator.persistence.repository.SagaInstanceRepository;
import com.saga.orchestrator.persistence.repository.SagaStepExecutionRepository;
import com.saga.orchestrator.showcase.*;
import com.saga.orchestrator.web.StartSagaRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies that WebSocketEventListener broadcasts saga execution events to the correct
 * STOMP topic when a saga is started via the REST API.
 *
 * SimpMessagingTemplate is mocked so no real WebSocket server is needed. We capture
 * all convertAndSend calls and assert the destination and payload are correct.
 */
class Phase8WebSocketTest extends AbstractWebIntegrationTest {

    @MockBean
    SimpMessagingTemplate messagingTemplate;

    @Autowired MockMvc                     mockMvc;
    @Autowired ObjectMapper                objectMapper;
    @Autowired SagaInstanceRepository      sagaRepo;
    @Autowired SagaStepExecutionRepository stepRepo;
    @Autowired CompensationStepRepository  compensationStepRepo;
    @Autowired SagaEntityLockRepository    lockRepo;
    @Autowired CreditCheckService          creditCheckService;
    @Autowired FundReservationService      fundReservationService;
    @Autowired DebitService                debitService;
    @Autowired CreditService               creditService;
    @Autowired NotificationService         notificationService;

    @BeforeEach
    void resetParticipants() {
        creditCheckService.reset();
        fundReservationService.reset();
        debitService.reset();
        creditService.reset();
        notificationService.reset();
    }

    @AfterEach
    void cleanup() {
        compensationStepRepo.deleteAll();
        lockRepo.deleteAll();
        stepRepo.deleteAll();
        sagaRepo.deleteAll();
    }

    @Test
    void startSaga_happyPath_websocketEventsPublishedForEachStep() throws Exception {
        String body = objectMapper.writeValueAsString(new StartSagaRequest(
                FundTransferSaga.SAGA_TYPE,
                objectMapper.readTree(objectMapper.writeValueAsString(
                        new TransferContext("ws-cust", "ws-src", "ws-dst", new BigDecimal("300")))),
                List.of(new EntityRef(FundTransferSaga.ENTITY_TYPE_ACCOUNT, "ws-src"))
        ));

        String responseBody = mockMvc.perform(post("/sagas")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String sagaId = objectMapper.readTree(responseBody).get("sagaId").asText();
        String expectedTopic = "/topic/sagas/" + sagaId;

        // Capture all destinations the messaging template was called with
        ArgumentCaptor<String> destinationCaptor = ArgumentCaptor.forClass(String.class);
        verify(messagingTemplate, atLeastOnce())
                .convertAndSend(destinationCaptor.capture(), any(Object.class));

        List<String> destinations = destinationCaptor.getAllValues();

        // At least one event should go to the saga's topic
        assertThat(destinations).anyMatch(d -> d.equals(expectedTopic));

        // Verify at least 5 events (one per step) were sent to this saga's topic
        long eventsForThisSaga = destinations.stream()
                .filter(d -> d.equals(expectedTopic))
                .count();
        assertThat(eventsForThisSaga)
                .as("5-step saga should emit at least 5 websocket events")
                .isGreaterThanOrEqualTo(5);
    }

    @Test
    void startSaga_eachEvent_containsCorrectPayloadFields() throws Exception {
        String body = objectMapper.writeValueAsString(new StartSagaRequest(
                FundTransferSaga.SAGA_TYPE,
                objectMapper.readTree(objectMapper.writeValueAsString(
                        new TransferContext("ws2-cust", "ws2-src", "ws2-dst", new BigDecimal("150")))),
                List.of(new EntityRef(FundTransferSaga.ENTITY_TYPE_ACCOUNT, "ws2-src"))
        ));

        mockMvc.perform(post("/sagas").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());

        ArgumentCaptor<Object> payloadCaptor = ArgumentCaptor.forClass(Object.class);
        verify(messagingTemplate, atLeastOnce())
                .convertAndSend(anyString(), payloadCaptor.capture());

        // Verify each captured payload has required fields
        List<String> requiredKeys = List.of(
                "sagaId", "sagaType", "step", "fromStatus", "toStatus",
                "terminal", "durationMs", "timestamp");

        payloadCaptor.getAllValues().stream()
                .filter(p -> p instanceof Map)
                .forEach(payload -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> m = (Map<String, Object>) payload;
                    assertThat(m).containsKeys(requiredKeys.toArray(new String[0]));
                });
    }
}
