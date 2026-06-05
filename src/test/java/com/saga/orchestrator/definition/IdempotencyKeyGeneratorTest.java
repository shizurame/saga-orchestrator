package com.saga.orchestrator.definition;

import com.saga.orchestrator.infrastructure.IdempotencyKeyGenerator;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class IdempotencyKeyGeneratorTest {

    private final IdempotencyKeyGenerator gen = new IdempotencyKeyGenerator();

    private static final UUID SAGA_A = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID SAGA_B = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Test
    void forwardKey_sameInputs_alwaysIdentical() {
        assertThat(gen.forwardKey(SAGA_A, 0, 1)).isEqualTo(gen.forwardKey(SAGA_A, 0, 1));
    }

    @Test
    void compensationKey_sameInputs_alwaysIdentical() {
        assertThat(gen.compensationKey(SAGA_A, 0, 1)).isEqualTo(gen.compensationKey(SAGA_A, 0, 1));
    }

    @Test
    void forwardVsCompensation_differentKeysForSameInputs() {
        // ":fwd:" vs ":comp:" separators must produce different keys
        assertThat(gen.forwardKey(SAGA_A, 2, 1)).isNotEqualTo(gen.compensationKey(SAGA_A, 2, 1));
    }

    @Test
    void differentStepIndex_differentForwardKeys() {
        assertThat(gen.forwardKey(SAGA_A, 0, 1)).isNotEqualTo(gen.forwardKey(SAGA_A, 1, 1));
    }

    @Test
    void differentAttempt_differentForwardKeys() {
        assertThat(gen.forwardKey(SAGA_A, 0, 1)).isNotEqualTo(gen.forwardKey(SAGA_A, 0, 2));
    }

    @Test
    void differentSagaId_differentForwardKeys() {
        assertThat(gen.forwardKey(SAGA_A, 0, 1)).isNotEqualTo(gen.forwardKey(SAGA_B, 0, 1));
    }

    /**
     * Separator collision test: without ":" separators, step=12/attempt=3 and
     * step=1/attempt=23 would both produce "...123" and collide.
     * The ":" separators prevent this: "...12:3" vs "...1:23".
     */
    @Test
    void separatorCollision_noCollisionBetweenAmbiguousInputs() {
        UUID sagaId = UUID.randomUUID();
        assertThat(gen.forwardKey(sagaId, 12, 3)).isNotEqualTo(gen.forwardKey(sagaId, 1, 23));
        assertThat(gen.compensationKey(sagaId, 12, 3)).isNotEqualTo(gen.compensationKey(sagaId, 1, 23));
    }

    @Test
    void outputIs64HexChars_sha256Digest() {
        String key = gen.forwardKey(SAGA_A, 0, 1);
        assertThat(key)
                .hasSize(64)
                .matches("[0-9a-f]{64}");
    }
}
