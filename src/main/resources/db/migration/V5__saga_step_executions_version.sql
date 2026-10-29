-- Optimistic locking for saga_step_executions. Without this, two concurrent callers racing
-- on the same execution row (one legitimately finishing it, another mistaking the in-flight
-- IN_PROGRESS row for an abandoned one to resume) have no atomic way to detect the conflict:
-- a plain read-then-write check still has a gap between reading the status and committing the
-- update. @Version closes it the same way saga_instance.version already does for SagaInstance.
ALTER TABLE saga_step_executions ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
