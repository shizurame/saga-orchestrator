CREATE TABLE compensation_steps (
    id               UUID          PRIMARY KEY,
    saga_id          UUID          NOT NULL REFERENCES saga_instance (id),
    step_index       INT           NOT NULL,
    action           VARCHAR(100)  NOT NULL,
    idempotency_key  VARCHAR(255)  NOT NULL,
    status           VARCHAR(50)   NOT NULL DEFAULT 'PENDING',
    attempt_number   INT           NOT NULL DEFAULT 0,
    result           JSONB,

    -- One compensation entry per (saga, step): plan is written atomically at EXECUTING → COMPENSATING.
    CONSTRAINT uq_comp_step UNIQUE (saga_id, step_index),
    CONSTRAINT uq_comp_idempotency_key UNIQUE (idempotency_key)
);

-- Partial index for the CompensationEngine query: load PENDING steps for a saga in reverse order.
CREATE INDEX idx_comp_steps_pending ON compensation_steps (saga_id, step_index DESC)
    WHERE status = 'PENDING';
