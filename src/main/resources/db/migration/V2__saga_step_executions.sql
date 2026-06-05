CREATE TABLE saga_step_executions (
    id               UUID          PRIMARY KEY,
    saga_id          UUID          NOT NULL REFERENCES saga_instance (id),
    step_index       INT           NOT NULL,
    attempt_number   INT           NOT NULL,
    idempotency_key  VARCHAR(255)  NOT NULL,
    status           VARCHAR(50)   NOT NULL,
    timeout_at       TIMESTAMPTZ   NOT NULL,
    started_at       TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    completed_at     TIMESTAMPTZ,
    result           JSONB,
    error            JSONB,

    -- Guarantees at most one row per (saga, step, attempt): prevents duplicate intent writes
    CONSTRAINT uq_step_attempt UNIQUE (saga_id, step_index, attempt_number),

    -- Idempotency key must be globally unique: a collision here means two different
    -- (sagaId, stepIndex, attempt) tuples produced the same hash — structural bug.
    CONSTRAINT uq_idempotency_key UNIQUE (idempotency_key)
);

-- Partial index on timeout_at: only IN_PROGRESS rows are scanned by the timeout scanner.
CREATE INDEX idx_step_exec_timeout ON saga_step_executions (timeout_at)
    WHERE status = 'IN_PROGRESS';
