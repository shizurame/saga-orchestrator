package com.saga.orchestrator.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.saga.orchestrator.definition.EntityRef;
import com.saga.orchestrator.definition.ExecutionResult;
import com.saga.orchestrator.definition.SagaStatus;
import com.saga.orchestrator.engine.SagaOrchestrator;
import com.saga.orchestrator.showcase.*;
import com.saga.orchestrator.web.StartSagaRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * HTTP-layer tests for POST /sagas and GET /sagas/{id}.
 * Uses MockMvc (WebEnvironment.MOCK) — no real HTTP server needed.
 */
class Phase7ControllerTest extends AbstractWebIntegrationTest {

    @Autowired MockMvc            mockMvc;
    @Autowired ObjectMapper       objectMapper;
    @Autowired SagaOrchestrator   orchestrator;
    @Autowired CreditCheckService  creditCheckService;
    @Autowired FundReservationService fundReservationService;
    @Autowired DebitService        debitService;
    @Autowired CreditService       creditService;
    @Autowired NotificationService notificationService;

    @BeforeEach
    void resetParticipants() {
        creditCheckService.reset();
        fundReservationService.reset();
        debitService.reset();
        creditService.reset();
        notificationService.reset();
    }

    @Test
    void happyPath_5Steps_sagaCompleted() throws Exception {
        // debitService.asyncMode=false (default) → returns Success synchronously
        String body = objectMapper.writeValueAsString(new StartSagaRequest(
                FundTransferSaga.SAGA_TYPE,
                objectMapper.readTree(objectMapper.writeValueAsString(
                        new TransferContext("cust-1", "acc-src-1", "acc-dst-1", new BigDecimal("1000")))),
                List.of(new EntityRef(FundTransferSaga.ENTITY_TYPE_ACCOUNT, "acc-src-1"))
        ));

        mockMvc.perform(post("/sagas").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.steps", hasSize(5)))
                .andExpect(jsonPath("$.steps[0].stepIndex").value(0))
                .andExpect(jsonPath("$.steps[4].status").value("COMPLETED"));
    }

    @Test
    void waitForCallback_sagaParks_thenCallbackResumes() throws Exception {
        // asyncMode=true → step 2 returns TimedOut → saga parks in WAITING_FOR_CALLBACK
        debitService.setAsyncMode(true);

        String body = objectMapper.writeValueAsString(new StartSagaRequest(
                FundTransferSaga.SAGA_TYPE,
                objectMapper.readTree(objectMapper.writeValueAsString(
                        new TransferContext("cust-2", "acc-src-2", "acc-dst-2", new BigDecimal("500")))),
                List.of(new EntityRef(FundTransferSaga.ENTITY_TYPE_ACCOUNT, "acc-src-2"))
        ));

        String responseBody = mockMvc.perform(post("/sagas").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("WAITING_FOR_CALLBACK"))
                .andReturn().getResponse().getContentAsString();

        String sagaId = objectMapper.readTree(responseBody).get("sagaId").asText();

        // switch debit back to sync so after callback we can verify forward execution
        debitService.setAsyncMode(false);

        // Deliver success callback for step 2
        mockMvc.perform(post("/sagas/{sagaId}/callback/2?success=true", sagaId))
                .andExpect(status().isOk());

        // GET should show COMPLETED now
        mockMvc.perform(get("/sagas/{id}", sagaId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));
    }

    @Test
    void entityLockConflict_returns409() throws Exception {
        // Pre-occupy the entity lock by starting a saga that parks (WAIT_FOR_CALLBACK)
        debitService.setAsyncMode(true);

        String body = objectMapper.writeValueAsString(new StartSagaRequest(
                FundTransferSaga.SAGA_TYPE,
                objectMapper.readTree(objectMapper.writeValueAsString(
                        new TransferContext("cust-3", "acc-src-3", "acc-dst-3", new BigDecimal("200")))),
                List.of(new EntityRef(FundTransferSaga.ENTITY_TYPE_ACCOUNT, "acc-src-3"))
        ));

        mockMvc.perform(post("/sagas").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("WAITING_FOR_CALLBACK"));

        // Second saga on the same source account → EntityLockConflictException → 409
        mockMvc.perform(post("/sagas").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict());
    }

    @Test
    void getSaga_notFound_returns404() throws Exception {
        mockMvc.perform(get("/sagas/{id}", "00000000-0000-0000-0000-000000000000"))
                .andExpect(status().isNotFound());
    }

    @Test
    void creditCheckFails_compensationRollsBack_sagaCompensated() throws Exception {
        // step 1 (reserve-funds) fails → step 0 (credit-check) compensated
        fundReservationService.setBehavior(
                () -> new ExecutionResult.Failure("insufficient funds", null));

        String body = objectMapper.writeValueAsString(new StartSagaRequest(
                FundTransferSaga.SAGA_TYPE,
                objectMapper.readTree(objectMapper.writeValueAsString(
                        new TransferContext("cust-4", "acc-src-4", "acc-dst-4", new BigDecimal("999999")))),
                List.of(new EntityRef(FundTransferSaga.ENTITY_TYPE_ACCOUNT, "acc-src-4"))
        ));

        mockMvc.perform(post("/sagas").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPENSATED"));
    }
}
