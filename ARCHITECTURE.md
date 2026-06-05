# Architecture

## The Central Problem

Multi-service business workflows fail partially. When step 3 of a 5-step payment saga fails
after steps 1 and 2 have already committed to external services, the system is inconsistent.
You cannot roll back a committed HTTP call. The only way forward is to execute compensating
transactions in reverse — and to do so correctly under every failure mode: orchestrator crash,
network timeout, duplicate delivery, and concurrent execution.

This document explains every architectural decision in terms of the failure it prevents.

---

## The One Invariant: Persist Intent Before Acting

Every state transition in this system follows the same protocol:

```
1. Write what you are about to do to the database (persist intent)
2. Do it
3. Write what happened (persist result)
```

If the orchestrator crashes between step 1 and step 3, on restart it finds the persisted intent,
derives the same idempotency key from it, and retries. Because the key is deterministic and
the participant honors it, the retry is safe.

This is the same principle as Write-Ahead Logging in storage systems (LSM, PostgreSQL):
write the intent (WAL entry) before mutating state (memtable/data page). The saga orchestrator
applies WAL thinking at the business transaction layer.

All seven hard problems in this system are instantiations of this one invariant.

---

## State Machine

### States

| State | Meaning |
|---|---|
| `STARTED` | Saga created, not yet claimed by any orchestrator node |
| `EXECUTING` | Forward steps are running |
| `WAITING_FOR_CALLBACK` | A step declared `WAIT_FOR_CALLBACK`; saga is parked until external system responds |
| `COMPENSATING` | A step failed; compensation plan written; running compensations in reverse |
| `COMPLETED` | All forward steps succeeded. Business transaction committed. |
| `COMPENSATED` | All compensations succeeded. Business transaction rolled back cleanly. |
| `FAILED` | A PIVOT step failed with no compensatable steps to undo, OR the saga was rejected before execution. |
| `DEAD_LETTER` | Compensation exhausted all retries. Manual intervention required. |

### Transitions

```
STARTED
  → EXECUTING              orchestrator claims the saga and begins step 0

EXECUTING
  → EXECUTING              step N succeeded; advance to step N+1
  → WAITING_FOR_CALLBACK   step declared WAIT_FOR_CALLBACK timeout mode; park saga
  → COMPENSATING           step failed (or TREAT_AS_FAILURE timeout fired); compensatable steps exist
  → FAILED                 step failed; PIVOT already passed; nothing to compensate
  → COMPLETED              last step succeeded

WAITING_FOR_CALLBACK
  → EXECUTING              callback received with SUCCESS
  → COMPENSATING           callback received with FAILURE

COMPENSATING
  → COMPENSATING           compensation step N succeeded; advance to N-1
  → COMPENSATED            all compensations succeeded
  → DEAD_LETTER            a compensation step exhausted all retries

COMPLETED    (terminal)
COMPENSATED  (terminal)
FAILED       (terminal)
DEAD_LETTER  (terminal)
```

### Why COMPLETED and COMPENSATED Are Distinct

In a payment system: `COMPLETED` = revenue confirmed, `COMPENSATED` = money returned,
`DEAD_LETTER` = finance team needs to investigate. Collapsing these into a single `FAILED`
state destroys auditability. Regulators in FinTech contexts expect these to be distinguishable.

### The Pure State Machine

`SagaStateMachine.java` is a pure function: `(currentState, event) → nextState`. It has no
database access, no Spring beans, no I/O. It can be exhaustively unit-tested without
infrastructure. The engines call it to compute the next state, then persist the transition,
then act.

---

## Saga Definition DSL

### Purpose

The DSL produces an immutable `SagaDefinition` that describes every step before execution begins.
It enforces the `COMPENSATABLE* → PIVOT? → RETRYABLE*` ordering constraint at build time —
not at runtime.

```java
SagaDefinition.<TransferContext>named("fund-transfer")
  .compensatableStep("credit-check")
    .action(ctx -> creditService.check(ctx.getCustomerId()))
    .compensation(ctx -> creditService.release(ctx.getReservationId()))
    .timeout(Duration.ofSeconds(5), TimeoutMode.RETRY_WITH_IDEMPOTENCY)
    .retryPolicy(RetryPolicy.exponential(3, Duration.ofMillis(200)))
  .compensatableStep("reserve-funds")
    .action(ctx -> fundService.reserve(ctx.getAmount()))
    .compensation(ctx -> fundService.release(ctx.getReservationId()))
    .timeout(Duration.ofSeconds(5), TimeoutMode.RETRY_WITH_IDEMPOTENCY)
  .pivotStep("debit-account")
    .action(ctx -> accountService.debit(ctx.getSourceAccount(), ctx.getAmount()))
    .timeout(Duration.ofSeconds(10), TimeoutMode.WAIT_FOR_CALLBACK)
  .retryableStep("credit-beneficiary")
    .action(ctx -> accountService.credit(ctx.getDestAccount(), ctx.getAmount()))
    .retryPolicy(RetryPolicy.fixed(5, Duration.ofSeconds(2)))
  .retryableStep("send-notification")
    .action(ctx -> notificationService.send(ctx.getTransferId()))
    .retryPolicy(RetryPolicy.fixed(3, Duration.ofSeconds(1)))
  .build();  // throws SagaDefinitionException if ordering is violated
```

### Validation Rule

The builder maintains a `currentPhase` enum (`COMPENSATABLE → PIVOT → RETRYABLE`) as steps
are added. Adding a COMPENSATABLE step after a PIVOT, or a PIVOT after a RETRYABLE, throws
`SagaDefinitionException` immediately. This makes it impossible to define an incorrect saga.

### Step Action Interface

```java
@FunctionalInterface
public interface StepAction<C extends SagaContext> {
    ExecutionResult execute(C context, String idempotencyKey);
}
```

The idempotency key is threaded through every step action. The action implementation is
responsible for honoring it (check-and-return-cached for internal bean actions). For HTTP
actions, the executor injects it as the `X-Idempotency-Key` header automatically.

---

## Idempotency System

### The Problem

An orchestrator calls participant service P. P executes. Before P's response arrives,
the network drops. From the orchestrator's perspective, this is indistinguishable from
P never having executed. If the orchestrator retries with a new key, P executes again.
Double charge. Double reservation. Double email.

### The Solution: Deterministic Key + Write Ordering

**Key generation:**
```
forwardKey = SHA-256(sagaId + ":fwd:" + stepIndex + ":" + attemptNumber)
compKey    = SHA-256(sagaId + ":comp:" + stepIndex + ":" + attemptNumber)
```

Separators (`:fwd:`, `:comp:`) prevent prefix-length collisions and namespace collisions
between a forward step and its compensation at the same index.

**Write ordering:**
```
1. UPDATE saga_instance SET step_status='IN_PROGRESS', attempt_number=N  ← persist intent
2. key = hash(sagaId, stepIndex, N)
3. Call participant with key (X-Idempotency-Key header or bean parameter)
4. UPDATE saga_instance SET step_status='COMPLETED', result=...          ← persist result
```

If the orchestrator crashes between step 1 and step 4:
- On restart: reads `step_status=IN_PROGRESS, attempt_number=N`
- Recomputes exactly the same key
- Retries with the same key
- Participant returns cached result if it executed, or executes fresh if it didn't
- Either way: exactly one execution

**Two layers of guarantee:**
- Layer 1 (orchestrator-side): write ordering ensures the same key is always sent on retry
- Layer 2 (participant-side): participant caches results against the key and returns without re-executing

Layer 2 is the participant's responsibility, not the orchestrator's. The orchestrator's contract:
"For the same (sagaId, stepIndex, attemptNumber), I guarantee the same key every time."

### The Concurrent Request Race

Two orchestrator threads both pick up the same saga step (e.g., recovery scanner + normal flow
both fire simultaneously). Both read `attempt_number=3`, both compute the same key, both call P.
P receives two identical calls, each with the same idempotency key.

Resolution: The entity lock (`lock_owner` + `fencing_token`) ensures only one orchestrator node
owns a given saga. Within a node, the step execution UPDATE uses optimistic locking — the second
writer gets 0 rows updated and aborts.

---

## Compensation Subsystem

### The Core Correctness Problem

If you write compensation steps lazily — only when needed — there is a crash window where
steps 1, 2, 3 have executed but the compensation plan for them doesn't exist. If the
orchestrator crashes at this point, on restart it knows a failure occurred but not what to
compensate.

### Atomic Compensation Plan Write (Intent Before First Compensation)

When the saga transitions `EXECUTING → COMPENSATING`, write the entire compensation plan in
the **same transaction** as the status change:

```sql
BEGIN;
  UPDATE saga_instance
    SET status = 'COMPENSATING', version = version + 1
    WHERE id = ? AND version = ?;

  INSERT INTO compensation_steps (saga_id, step_index, action, idempotency_key, status)
  VALUES
    (?, 1, 'release-funds',   hash(sagaId, ":comp:", 1, 0), 'PENDING'),
    (?, 0, 'release-credit',  hash(sagaId, ":comp:", 0, 0), 'PENDING');
COMMIT;
```

Only after this transaction commits does the first compensation execute.

**Why at transition time, not at saga creation:** Forward step results (e.g., `reservationId`,
`authorizationId`) are required to construct compensation actions. These don't exist at saga
creation time. Writing at transition time means you have real data and real action parameters.

### Compensation Execution

Compensation steps execute in **reverse** forward-step order. Each compensation:
1. Generates its own deterministic idempotency key
2. Persists `compensation_status=IN_PROGRESS` before calling the participant
3. Marks `compensation_status=COMPLETED` on success
4. Applies its own retry policy on failure

PIVOT and RETRYABLE steps are never compensated. The compensation executor skips them and
processes only COMPENSATABLE steps.

If a compensation step exhausts its retry budget: move the saga to `DEAD_LETTER`.

---

## Optimistic Locking

Every `saga_instance` row has a `version BIGINT` column. Every state-transition UPDATE
includes a version check:

```sql
UPDATE saga_instance
SET status = :newStatus, current_step = :step, version = version + 1
WHERE id = :sagaId AND version = :expectedVersion;
```

If the UPDATE affects 0 rows, another writer updated this saga since we read it. Throw
`OptimisticLockException` and let the engine retry (it will re-read the current state and
determine if its update is still applicable).

This handles the concurrent-reply case: if step result reply-A and reply-B arrive
simultaneously for the same saga, only one can commit. The other retries and finds the
saga has already advanced — it exits cleanly.

**Spring Data JPA `@Version`** implements this automatically on `SagaInstance.version`.
The underlying SQL is identical to the above.

---

## Heartbeat and Failover

### Lock Ownership Model

Each `saga_instance` row carries:

```sql
lock_owner       VARCHAR   -- orchestrator node ID (e.g., UUID assigned at startup)
heartbeat_at     TIMESTAMP -- last time owner confirmed liveness
lease_expires_at TIMESTAMP -- heartbeat_at + lease_duration (precomputed, indexed)
fencing_token    BIGINT    -- incremented on every claim; used for stale-writer rejection
```

### Claiming a Saga

```sql
UPDATE saga_instance
SET lock_owner = :myNodeId,
    heartbeat_at = NOW(),
    lease_expires_at = NOW() + INTERVAL '30 seconds',
    fencing_token = fencing_token + 1
WHERE id = :sagaId
  AND (lock_owner IS NULL OR lease_expires_at < NOW())
RETURNING fencing_token;
```

1 row affected → you own it. 0 rows → another node owns it (lease not expired yet).

### Heartbeat

`HeartbeatService` runs on a `@Scheduled` background thread every 10 seconds:

```sql
UPDATE saga_instance
SET heartbeat_at = NOW(), lease_expires_at = NOW() + INTERVAL '30 seconds'
WHERE lock_owner = :myNodeId AND status IN ('EXECUTING', 'COMPENSATING', 'WAITING_FOR_CALLBACK');
```

### Fencing Tokens (The GC Pause Problem)

Without fencing tokens: node A owns a saga, goes into a long GC pause, its lease expires, node B
steals the lock and advances the saga. Node A wakes up from GC and also tries to advance the saga.
Both succeed if the only protection is the lease check — node B already updated the lease, but
node A's write doesn't check the lease at all.

With fencing tokens: node A received token 41 when it claimed the saga. Node B claimed with token
42. Every state-transition UPDATE by node A includes `WHERE fencing_token = 41`. Node B's claim
set `fencing_token = 42`. Node A's UPDATE returns 0 rows. Node A detects this, logs a warning,
and stops processing. Node B is the sole writer.

**The fencing token is the reason `lock_owner` alone is not sufficient.**

### Recovery After Crash

`OrphanedSagaScanner` (Quartz job) runs on startup and every 30 seconds:
```sql
SELECT * FROM saga_instance
WHERE status IN ('EXECUTING', 'COMPENSATING', 'WAITING_FOR_CALLBACK')
  AND lease_expires_at < NOW();
```

For each orphaned saga: claim it (via the claim UPDATE above), re-read current state, resume from
last persisted step using the already-persisted `attempt_number` and the deterministic key.

---

## Entity-Level Saga Serialization

### The Problem

Two sagas operate on the same customer account simultaneously. Both read balance $100, both
pass the credit check. Both commit. Customer is overdrafted. This is the lost-update problem
at the business entity layer, not the database row layer.

### Lock Table

```sql
CREATE TABLE saga_entity_locks (
    entity_type  VARCHAR NOT NULL,
    entity_id    VARCHAR NOT NULL,
    saga_id      UUID    NOT NULL,
    acquired_at  TIMESTAMP NOT NULL DEFAULT NOW(),
    PRIMARY KEY (entity_type, entity_id)
);
```

At saga start, sort all requested entity refs by `(entity_type, entity_id)` lexicographically before acquiring. Always acquire in this order — because every caller uses the same ordering, circular wait is structurally impossible and deadlock cannot occur.

```sql
INSERT INTO saga_entity_locks (entity_type, entity_id, saga_id)
VALUES ('ACCOUNT', :accountId, :sagaId)
ON CONFLICT (entity_type, entity_id) DO NOTHING;
```

Check affected rows: 1 → lock acquired. 0 → another saga holds it. Throw `EntityLockConflictException`; the API layer returns 409 Conflict. Internal queuing is deliberately not used — a saga in `WAIT_FOR_CALLBACK` can hold an entity lock for minutes (bank settlement, KYC); no HTTP connection or internal queue can absorb that. Callers retry with exponential backoff.

### Lock Release

Released in the **same transaction** as the terminal state write (COMPLETED, COMPENSATED,
FAILED, DEAD_LETTER):

```sql
BEGIN;
  UPDATE saga_instance SET status='COMPLETED', version=version+1 WHERE id=? AND version=?;
  DELETE FROM saga_entity_locks WHERE entity_type=? AND entity_id=? AND saga_id=?;
COMMIT;
```

If the terminal write and lock release are separate transactions, a crash between them leaves
the lock orphaned permanently. Atomic write eliminates this window.

**DEAD_LETTER also releases:** A saga in DEAD_LETTER cannot hold entity locks indefinitely —
every subsequent saga for that entity would be permanently blocked.

### Lock Granularity

Lock at the **resource** level, not the customer level:
- `ACCOUNT:ACC-123` — correct granularity
- `CUSTOMER:CUST-456` — too coarse; blocks all concurrent operations for a customer

A customer with two independent accounts should be able to run concurrent sagas on each.

---

## Pivot Transaction Classification

### The Three Step Types

Based on Garcia-Molina & Salem (1987):

**COMPENSATABLE:** Can be undone by a corresponding compensation transaction. Must precede the
PIVOT step. Compensation is a real business operation, not a database rollback.

**PIVOT:** The point of no return. If it succeeds, the saga is committed to completing forward.
Cannot be compensated — only refunded (which is a different forward saga). A saga has at most one
PIVOT step. If the PIVOT fails, the saga moves to FAILED (compensatable steps before it are
compensated, but the PIVOT itself is not).

**RETRYABLE:** Guaranteed to eventually succeed with sufficient retries. No compensation needed
or possible. Must follow the PIVOT step. These steps are guaranteed to not fail permanently.

### Example (Fund Transfer)

```
credit-check      → COMPENSATABLE (can un-reserve)
reserve-funds     → COMPENSATABLE (can release reservation)
debit-account     → PIVOT         (once debited, you refund via a new saga; you don't un-debit)
credit-beneficiary→ RETRYABLE     (must eventually credit; network retries will succeed)
send-notification → RETRYABLE     (email must eventually send)
```

### Enforcement at DSL Build Time

The builder tracks `currentPhase`. Adding a COMPENSATABLE step after a PIVOT throws
`SagaDefinitionException` at `build()` time. The orchestrator never sees an invalid saga
definition at runtime — invalid ones fail at application startup.

---

## Timeout Semantics

### The Three-Way Ambiguity

A step timeout means one of three things:
1. The call never reached the participant (network failure before delivery)
2. The call reached the participant, it executed, but the response was lost
3. The step is genuinely long-running and hasn't finished yet

These are indistinguishable from the orchestrator's perspective. The configured `TimeoutMode`
is the developer's declaration of which case applies.

### RETRY_WITH_IDEMPOTENCY

Treat the timeout as transient. Retry with the same deterministic idempotency key.
Safe for participants that implement idempotency. Correct for both cases 1 and 2:
- Case 1: participant executes fresh; returns result
- Case 2: participant returns cached result
No compensation triggered.

**When to use:** Any participant that honors `X-Idempotency-Key`.

### TREAT_AS_FAILURE

Treat the timeout as a permanent step failure. Trigger compensation.
Only safe if you are certain the call did not execute (e.g., non-idempotent external API
where you cannot retry without risk, and you are willing to accept the false-failure cost).

**When to use:** Non-idempotent third-party APIs with no retry support, where the business
cost of a false compensation is acceptable.

### WAIT_FOR_CALLBACK

The step is genuinely async. Park the saga in `WAITING_FOR_CALLBACK`. Do not trigger
compensation. Do not retry. Wait for an explicit callback.

```
POST /sagas/{sagaId}/callback/{stepIndex}
Body: { "status": "SUCCESS" | "FAILURE", "result": {...} }
```

The callback endpoint correlates via `sagaId + stepIndex`, validates the saga is in
`WAITING_FOR_CALLBACK` state, and advances the state machine.

**When to use:** Bank settlement windows, KYC checks, manual approval workflows, any step
where the processing time is measured in minutes or hours.

### Timeout Scanner

A Quartz job runs every 5 seconds:
```sql
SELECT * FROM saga_step_executions
WHERE status = 'IN_PROGRESS' AND timeout_at < NOW();
```

For each row, reads the step's configured `TimeoutMode` and applies the appropriate behavior.

---

## Step Execution Detail

### Write-Ordering Protocol (Per Step)

```
1. BEGIN transaction
   UPDATE saga_instance SET current_step=N, attempt_number=M, version=version+1
     WHERE id=? AND version=?;  ← optimistic lock check
   INSERT INTO saga_step_executions (saga_id, step_index, attempt_number, idempotency_key,
     status, timeout_at) VALUES (?, N, M, hash(sagaId,N,M), 'IN_PROGRESS', NOW()+timeout);
   COMMIT;

2. executor.execute(context, idempotencyKey)
   → BeanStepExecutor: direct method call with idempotencyKey parameter
   → HttpStepExecutor: RestClient call with X-Idempotency-Key: <key> header

3. BEGIN transaction
   UPDATE saga_step_executions SET status='COMPLETED', result=?, completed_at=NOW()
     WHERE saga_id=? AND step_index=N AND attempt_number=M;
   UPDATE saga_instance SET version=version+1 WHERE id=? AND version=?;
   COMMIT;
```

Crash between step 1 and step 3: on restart, reads `IN_PROGRESS` step, re-derives same key,
retries step 2. Participant receives the same key and returns cached result.

### HTTP Step Executor

- Uses Spring 6 `RestClient` (synchronous — not WebClient)
- Per-step timeout configured at the request factory level
- Injects `X-Idempotency-Key` header on every request
- Maps `ResourceAccessException` (timeout) to `ExecutionResult.TimedOut`
- Does not retry internally; retry policy is applied by the engine

**Why RestClient, not WebClient:** Each saga executes sequentially — one step at a time per saga.
There is no benefit to reactive I/O here; the saga thread blocks on each step regardless. WebClient
with `.block()` is reactive overhead with no throughput gain. RestClient is simpler, synchronous,
and correct.

### Bean Step Executor

- Wraps `StepAction.execute()` in `CompletableFuture.get(timeout)` on a worker thread; returns `ExecutionResult.TimedOut` on expiry — identical path to `HttpStepExecutor`
- The bean worker thread may complete after the orchestrator has moved on; if it wrote to its idempotency cache, the retry returns the cached result — no double execution
- Idempotency key passed as parameter; bean implements check-and-return-cached
- `WAIT_FOR_CALLBACK` mode is disallowed for bean steps and rejected at DSL build time — this mode is meaningful only for genuinely async external systems (bank settlement, KYC), not in-process calls

---

## Crash Recovery

### Startup Scan

`OrphanedSagaScanner` runs immediately on application startup (Quartz `.startNow()` trigger)
and then every 30 seconds:

1. Find sagas with expired leases: `WHERE lease_expires_at < NOW() AND status IN ('EXECUTING', ...)`
2. For each: attempt to claim (claim UPDATE must succeed — prevents two nodes racing)
3. On successful claim: re-read current step + status + attempt_number
4. Resume from last persisted state:
   - `step_status = IN_PROGRESS` → retry the step with the existing idempotency key
   - `status = EXECUTING` → advance to next step
   - `status = COMPENSATING` → resume compensation from last PENDING compensation step
   - `status = WAITING_FOR_CALLBACK` → leave parked; timeout scanner handles expiry

### What Makes Recovery Correct

- The idempotency key is deterministic: `hash(sagaId, stepIndex, attempt)` — same inputs, same key, always
- The attempt number is persisted before the call is made — so on restart, we always know what attempt we're on
- The compensation plan is already written — on restart in COMPENSATING state, the compensation_steps table has everything needed
- The fencing token prevents the old owner from interfering if it wakes up late

---

## Schema Summary

```sql
-- Saga lifecycle and lock ownership
saga_instance (
    id               UUID PRIMARY KEY,
    saga_type        VARCHAR(100)  NOT NULL,
    status           VARCHAR(50)   NOT NULL,
    current_step     INT           NOT NULL DEFAULT 0,
    context          JSONB         NOT NULL,
    version          BIGINT        NOT NULL DEFAULT 0,
    lock_owner       VARCHAR(100),
    heartbeat_at     TIMESTAMP,
    lease_expires_at TIMESTAMP,
    fencing_token    BIGINT        NOT NULL DEFAULT 0,
    created_at       TIMESTAMP     NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMP     NOT NULL DEFAULT NOW()
);

-- Per-step-per-attempt execution state (idempotency + timeout tracking)
saga_step_executions (
    id               UUID PRIMARY KEY,
    saga_id          UUID          NOT NULL REFERENCES saga_instance(id),
    step_index       INT           NOT NULL,
    attempt_number   INT           NOT NULL,
    idempotency_key  VARCHAR(255)  NOT NULL,
    status           VARCHAR(50)   NOT NULL,  -- IN_PROGRESS | COMPLETED | FAILED | TIMED_OUT
    timeout_at       TIMESTAMP     NOT NULL,
    started_at       TIMESTAMP     NOT NULL DEFAULT NOW(),
    completed_at     TIMESTAMP,
    result           JSONB,
    error            JSONB,
    UNIQUE (saga_id, step_index, attempt_number),
    UNIQUE (idempotency_key)
);

-- Compensation plan (written atomically at EXECUTING → COMPENSATING transition)
compensation_steps (
    id               UUID PRIMARY KEY,
    saga_id          UUID          NOT NULL REFERENCES saga_instance(id),
    step_index       INT           NOT NULL,
    action           VARCHAR(100)  NOT NULL,
    idempotency_key  VARCHAR(255)  NOT NULL,
    status           VARCHAR(50)   NOT NULL DEFAULT 'PENDING',
    attempt_number   INT           NOT NULL DEFAULT 0,
    result           JSONB,
    UNIQUE (saga_id, step_index),
    UNIQUE (idempotency_key)
);

-- Entity-level saga serialization
saga_entity_locks (
    entity_type      VARCHAR(100)  NOT NULL,
    entity_id        VARCHAR(255)  NOT NULL,
    saga_id          UUID          NOT NULL,
    acquired_at      TIMESTAMP     NOT NULL DEFAULT NOW(),
    PRIMARY KEY (entity_type, entity_id)
);
```

**Indexes required for performance:**
```sql
CREATE INDEX idx_saga_lease_expires ON saga_instance(lease_expires_at)
    WHERE status IN ('EXECUTING', 'COMPENSATING', 'WAITING_FOR_CALLBACK');

CREATE INDEX idx_step_exec_timeout ON saga_step_executions(timeout_at)
    WHERE status = 'IN_PROGRESS';

CREATE INDEX idx_comp_steps_pending ON compensation_steps(saga_id)
    WHERE status = 'PENDING';
```

---

## What We Deliberately Did Not Build

**gRPC support:** The correctness problems (idempotency, timeout ambiguity, compensation
atomicity) are transport-agnostic. gRPC adds proto file management and code generation without
teaching anything new about the failure modes. HTTP + bean calls cover both the network and
in-process cases.

**Redis or ZooKeeper for locking:** PostgreSQL-based leases + fencing tokens are sufficient and
eliminate an external dependency. Adding Redis would demonstrate operational complexity, not
distributed systems understanding.

**Temporal-style event history replay:** Building a full durable execution model (workflow code
replayed from event log) would take months and would shift the project from "I understand
what Temporal is doing" to "I am building a worse Temporal." The value is understanding the
correctness properties, not rebuilding the full product.

**Choreography mode:** Choreography (services reacting to events, no central coordinator) has
no single source of truth for saga state. The orchestrator is the portfolio's point — centralized
state, explicit compensation, deterministic recovery.

**Optimistic entity-level locking:** Optimistic locking at the entity level (version numbers on
business entity rows) fails late — after external steps have already executed. The pessimistic
approach (lock table acquired before first step) fails early and prevents the inconsistency
from ever being observable.
