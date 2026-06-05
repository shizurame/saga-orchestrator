package com.saga.orchestrator.definition;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SagaDefinitionRegistryTest {

    record TestContext(String id) implements SagaContext {}

    private SagaDefinitionRegistry registry;
    private SagaDefinition<TestContext> definition;

    @BeforeEach
    void setUp() {
        registry = new SagaDefinitionRegistry();
        definition = SagaDefinition.named("fund-transfer", TestContext.class)
            .compensatableStep("check").action((ctx, key) -> new ExecutionResult.Success("ok"))
            .build();
    }

    @Test
    void registerAndLookup_returnsRegisteredDefinition() {
        registry.register(definition);
        assertThat(registry.lookup("fund-transfer")).isSameAs(definition);
    }

    @Test
    void isRegistered_trueAfterRegister() {
        assertThat(registry.isRegistered("fund-transfer")).isFalse();
        registry.register(definition);
        assertThat(registry.isRegistered("fund-transfer")).isTrue();
    }

    @Test
    void duplicateRegistration_throws() {
        registry.register(definition);
        assertThatThrownBy(() -> registry.register(definition))
            .isInstanceOf(SagaDefinitionException.class)
            .hasMessageContaining("already registered");
    }

    @Test
    void lookupUnknownType_throws() {
        assertThatThrownBy(() -> registry.lookup("nonexistent"))
            .isInstanceOf(SagaDefinitionException.class)
            .hasMessageContaining("nonexistent");
    }

    @Test
    void multipleDistinctDefinitions_coexist() {
        SagaDefinition<TestContext> second = SagaDefinition.named("order-saga", TestContext.class)
            .retryableStep("notify").action((ctx, key) -> ExecutionResult.TimedOut.INSTANCE)
            .build();

        registry.register(definition);
        registry.register(second);

        assertThat(registry.lookup("fund-transfer").sagaType()).isEqualTo("fund-transfer");
        assertThat(registry.lookup("order-saga").sagaType()).isEqualTo("order-saga");
    }
}
