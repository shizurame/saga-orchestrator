CREATE TABLE saga_instance (
    id               UUID          PRIMARY KEY,
    saga_type        VARCHAR(100)  NOT NULL,
    status           VARCHAR(50)   NOT NULL,
    current_step     INT           NOT NULL DEFAULT 0,
    context          JSONB         NOT NULL,
    version          BIGINT        NOT NULL DEFAULT 0,
    lock_owner       VARCHAR(100),
    heartbeat_at     TIMESTAMPTZ,
    lease_expires_at TIMESTAMPTZ,
    fencing_token    BIGINT        NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);

-- Partial index on lease expiry: only rows that can be stolen by the recovery scanner need this index.
-- Filtered to active statuses keeps it small even with millions of completed sagas.
CREATE INDEX idx_saga_lease_expires ON saga_instance (lease_expires_at)
    WHERE status IN ('EXECUTING', 'COMPENSATING', 'WAITING_FOR_CALLBACK');
