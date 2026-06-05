package com.saga.orchestrator.engine;

import com.saga.orchestrator.definition.SagaEvent;
import com.saga.orchestrator.definition.SagaStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Stream;

import static com.saga.orchestrator.definition.SagaEvent.*;
import static com.saga.orchestrator.definition.SagaStatus.*;
import static org.assertj.core.api.Assertions.*;

class SagaStateMachineTest {

    private final SagaStateMachine fsm = new SagaStateMachine();

    // ── Valid transitions ─────────────────────────────────────────────────────

    @ParameterizedTest(name = "{0} + {1} → {2}")
    @MethodSource("validTransitions")
    void valid_transition_producesExpectedStatus(SagaStatus from, SagaEvent event, SagaStatus expected) {
        assertThat(fsm.transition(from, event)).isEqualTo(expected);
    }

    static Stream<Arguments> validTransitions() {
        return Stream.of(
            // STARTED
            Arguments.of(STARTED,              STEP_SUCCEEDED,         EXECUTING),
            // EXECUTING — all 5 outgoing events
            Arguments.of(EXECUTING,            STEP_SUCCEEDED,         EXECUTING),
            Arguments.of(EXECUTING,            SAGA_COMPLETED,         COMPLETED),
            Arguments.of(EXECUTING,            STEP_FAILED,            COMPENSATING),
            Arguments.of(EXECUTING,            NO_COMPENSATABLES,      FAILED),
            Arguments.of(EXECUTING,            STEP_TIMED_OUT,         WAITING_FOR_CALLBACK),
            // WAITING_FOR_CALLBACK
            Arguments.of(WAITING_FOR_CALLBACK, CALLBACK_SUCCESS,       EXECUTING),
            Arguments.of(WAITING_FOR_CALLBACK, SAGA_COMPLETED,         COMPLETED),   // last step via callback
            Arguments.of(WAITING_FOR_CALLBACK, CALLBACK_FAILURE,       COMPENSATING),
            // COMPENSATING — all 4 outgoing events
            Arguments.of(COMPENSATING,         COMPENSATION_SUCCEEDED, COMPENSATING),
            Arguments.of(COMPENSATING,         ALL_COMPENSATED,        COMPENSATED),
            Arguments.of(COMPENSATING,         COMPENSATION_FAILED,    COMPENSATING),
            Arguments.of(COMPENSATING,         COMPENSATION_EXHAUSTED, DEAD_LETTER)
        );
    }

    // ── Invalid transitions — terminal states ─────────────────────────────────

    @ParameterizedTest(name = "{0} + {1} → IllegalStateException")
    @MethodSource("terminalStateTransitions")
    void terminalStatus_anyEvent_throws(SagaStatus terminal, SagaEvent event) {
        assertThatThrownBy(() -> fsm.transition(terminal, event))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(terminal.name())
                .hasMessageContaining(event.name());
    }

    static Stream<Arguments> terminalStateTransitions() {
        Set<SagaStatus> terminals = Set.of(COMPLETED, COMPENSATED, FAILED, DEAD_LETTER);
        return Arrays.stream(SagaEvent.values())
                .flatMap(event -> terminals.stream().map(s -> Arguments.of(s, event)));
    }

    // ── Invalid transitions — non-terminal states ─────────────────────────────

    @Test
    void started_invalidEvents_throw() {
        Set<SagaEvent> invalid = Set.of(
            SAGA_COMPLETED, STEP_FAILED, NO_COMPENSATABLES, STEP_TIMED_OUT,
            CALLBACK_SUCCESS, CALLBACK_FAILURE,
            COMPENSATION_SUCCEEDED, ALL_COMPENSATED, COMPENSATION_FAILED, COMPENSATION_EXHAUSTED
        );
        invalid.forEach(event ->
            assertThatThrownBy(() -> fsm.transition(STARTED, event))
                    .isInstanceOf(IllegalStateException.class));
    }

    @Test
    void executing_invalidEvents_throw() {
        Set<SagaEvent> valid = Set.of(STEP_SUCCEEDED, SAGA_COMPLETED, STEP_FAILED,
                                      NO_COMPENSATABLES, STEP_TIMED_OUT);
        Arrays.stream(SagaEvent.values())
              .filter(e -> !valid.contains(e))
              .forEach(event ->
                  assertThatThrownBy(() -> fsm.transition(EXECUTING, event))
                          .isInstanceOf(IllegalStateException.class));
    }

    @Test
    void waitingForCallback_invalidEvents_throw() {
        Set<SagaEvent> valid = Set.of(CALLBACK_SUCCESS, SAGA_COMPLETED, CALLBACK_FAILURE);
        Arrays.stream(SagaEvent.values())
              .filter(e -> !valid.contains(e))
              .forEach(event ->
                  assertThatThrownBy(() -> fsm.transition(WAITING_FOR_CALLBACK, event))
                          .isInstanceOf(IllegalStateException.class));
    }

    @Test
    void compensating_invalidEvents_throw() {
        Set<SagaEvent> valid = Set.of(COMPENSATION_SUCCEEDED, ALL_COMPENSATED,
                                      COMPENSATION_FAILED, COMPENSATION_EXHAUSTED);
        Arrays.stream(SagaEvent.values())
              .filter(e -> !valid.contains(e))
              .forEach(event ->
                  assertThatThrownBy(() -> fsm.transition(COMPENSATING, event))
                          .isInstanceOf(IllegalStateException.class));
    }

    // ── Idempotent self-loops ─────────────────────────────────────────────────

    @Test
    void executing_stepSucceeded_staysExecuting() {
        assertThat(fsm.transition(EXECUTING, STEP_SUCCEEDED)).isEqualTo(EXECUTING);
    }

    @Test
    void compensating_compensationSucceeded_staysCompensating() {
        assertThat(fsm.transition(COMPENSATING, COMPENSATION_SUCCEEDED)).isEqualTo(COMPENSATING);
    }

    @Test
    void compensating_compensationFailed_staysCompensating() {
        assertThat(fsm.transition(COMPENSATING, COMPENSATION_FAILED)).isEqualTo(COMPENSATING);
    }
}
