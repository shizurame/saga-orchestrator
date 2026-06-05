CREATE TABLE saga_entity_locks (
    entity_type  VARCHAR(100) NOT NULL,
    entity_id    VARCHAR(255) NOT NULL,
    saga_id      UUID         NOT NULL,
    acquired_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    -- Composite PK enforces one lock per entity at the DB level.
    -- ON CONFLICT DO NOTHING in the INSERT is how acquire-or-fail works without a separate SELECT.
    PRIMARY KEY (entity_type, entity_id)
);
