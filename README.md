# Saga Orchestrator

A distributed saga orchestrator built in Java 21 + Spring Boot 3.3, exercising the hardest
correctness problems in multi-service financial workflows: exactly-once execution, crash
recovery, timeout ambiguity, and compensation atomicity.

---

## The Problem

Multi-service business workflows fail partially. When step 3 of a 5-step payment saga fails
after steps 1 and 2 have already committed to external services, the system is inconsistent.
You cannot roll back a committed HTTP call. The only way forward is compensation — executing
undo transactions in reverse — and doing so correctly under every failure mode.

---

## Seven Hard Problems Solved

| Problem | Mechanism |
|---------|-----------|
| **Exactly-once step execution** | Deterministic SHA-256 idempotency keys; write intent before acting |
| **Crash recovery** | Heartbeat leases + fencing tokens; orphaned saga scanner resumes in ≤30s |
| **Timeout ambiguity** | Three-mode semantics: retry-with-same-key, treat-as-failure, or park for callback |
| **Compensation atomicity** | Full plan written in one transaction at the moment of failure, before first undo |
| **Concurrent saga serialization** | Entity lock table with `ON CONFLICT DO NOTHING`; canonical sort prevents deadlock |
| **Stale-writer rejection** | Fencing token checked before every saga-state write; a GC-paused node's stale write is rejected once another node has claimed the saga |
| **Step classification** | DSL enforces `COMPENSATABLE* → PIVOT? → RETRYABLE*` at build time, not runtime |

---

## The Fund-Transfer Saga (5-Step Demo)

```
 Step 0: credit-check       COMPENSATABLE   ──→ compensation: release credit hold
 Step 1: reserve-funds      COMPENSATABLE   ──→ compensation: release reservation
 Step 2: debit-account      PIVOT           ──→ (no compensation; refund is a new saga)
 Step 3: credit-beneficiary RETRYABLE       ──→ must eventually complete; no undo
 Step 4: send-notification  RETRYABLE       ──→ must eventually complete; no undo
```

The debit step uses `WAIT_FOR_CALLBACK` mode — the saga parks in `WAITING_FOR_CALLBACK`
while the bank settles asynchronously, holding the entity lock. An external system delivers
the result via `POST /sagas/{sagaId}/callback/{stepIndex}`.

If step 1 (reserve-funds) fails: compensations run in reverse — step 1 then step 0.
If step 2 (debit, the PIVOT) fails: step 1 and step 0 compensate. Step 2 itself does not.
If step 3 or 4 fail: they retry indefinitely per their retry policy. No compensation possible.

---

## State Machine

```
STARTED → EXECUTING → COMPLETED           (happy path)
                    → COMPENSATING → COMPENSATED   (failure before pivot)
                    → COMPENSATING → DEAD_LETTER   (compensation exhausted)
                    → FAILED                       (pivot fails, nothing to compensate)
                    → WAITING_FOR_CALLBACK → EXECUTING (callback success)
                                          → COMPENSATING (callback failure)
```

---

## Idempotency Protocol

Every step follows write-ahead ordering:

```
1. Persist intent  (REQUIRES_NEW TX commits before action)
   key = SHA-256(sagaId + ":fwd:" + stepIndex + ":" + attempt)

2. Execute action  (no transaction; participant receives key as X-Idempotency-Key)

3. Persist result  (REQUIRES_NEW TX commits result + advances saga)
```

Crash between steps 1 and 3: on recovery, the same key is derived from persisted state.
The participant returns its cached result. Exactly one execution, regardless of how many retries.

---

## Quick Start

**Prerequisites:** Java 21, Maven 3.9+, Docker

```bash
# Start PostgreSQL
docker-compose up -d

# Run all tests (169 tests including chaos and integration; benchmarks excluded)
mvn test

# Run benchmarks only (separate Maven profile — see pom.xml)
mvn test -Pbenchmark

# Start the application
mvn spring-boot:run
```

**Endpoints:**
```
POST /sagas                              Start a saga
GET  /sagas/{id}                         Query status + step history
POST /sagas/{id}/callback/{step}         Deliver async callback result
GET  /actuator/prometheus                Prometheus metrics
ws://localhost:8080/ws                   STOMP WebSocket (live events)
```

**Start a fund-transfer saga:**
```bash
curl -X POST http://localhost:8080/sagas \
  -H "Content-Type: application/json" \
  -d '{
    "sagaType": "fund-transfer",
    "context": {
      "customerId": "CUST-001",
      "sourceAccountId": "ACC-123",
      "destAccountId": "ACC-456",
      "amount": 500.00
    },
    "lockedEntities": [
      {"entityType": "ACCOUNT", "entityId": "ACC-123"},
      {"entityType": "ACCOUNT", "entityId": "ACC-456"}
    ]
  }'
```

---

## Benchmark Results

Measured with JMH 1.37, `@Fork(0)`, against Testcontainers PostgreSQL 16 (Apple M3, Java 21).
Median of 3 isolated runs on 2026-10-29 (`mvn test -Pbenchmark`); see BENCHMARK_RESULTS.md for
the full range and per-run numbers — the error bars on these are wide (3 iterations, `@Fork(0)`).

| Benchmark | Median | Range | Unit | What it measures |
|-----------|--------|-------|------|-----------------|
| `sagaStepLatency` | **2,551** | 2,277–2,567 | µs/op | 1-step saga: 3 REQUIRES_NEW DB round-trips |
| `compensationLatency` | **5,593** | 5,199–6,420 | µs/op | 2-step failure → COMPENSATED: 8 DB writes |
| `sagaThroughput` | ~144 | — | ops/s | 5-step fund-transfer, single-threaded |
| `entityLockContention` | ~137 | — | ops/s | Per-account serialization per iteration |

See [BENCHMARK_RESULTS.md](BENCHMARK_RESULTS.md) for full interpretation.

---

## Test Coverage Highlights

| Test | What it proves |
|------|----------------|
| `CrashAtEachStepTest.crashAtStep_recoveryResumesSagaToCompleted` (parameterized 0..4) | Scanner claims and resumes correctly from a crash at a step *boundary* (no step in flight) |
| `CrashAtEachStepTest.crashMidStep_recoveryReusesExistingKey` (parameterized 0..4) | Scanner resumes a step genuinely IN_PROGRESS (crash between intent and result) by reusing the existing row/key, not starting a new attempt |
| `StaleWriterRejectedTest` | A node whose fencing token is stale gets `FencedOutException` and writes nothing; the new owner's write with the correct token succeeds |
| `IdempotencyUnder1000RetriesTest` | 1000 concurrent callers with the same key → exactly 1 execution (proves the **participant's** dedup cache, not the orchestrator's write path — see ARCHITECTURE.md) |
| `ConcurrentSagaSameAccountTest` | Entity lock serializes two sagas; second blocked until first completes |
| `CompensationUnderCrashTest` | Mid-compensation crash; scanner resumes; all steps compensated |
| `Phase5IntegrationTest` | Reverse ordering; crash recovery; idempotent compensation |
| `Phase6IntegrationTest` | All three timeout modes; callback delivery; orphan recovery |

169 tests, 0 mocks for the database — all integration tests run against a real PostgreSQL instance
via Testcontainers. Quartz auto-startup disabled in tests; scanner services are injected directly.
Benchmarks (`BenchmarkSuiteTest`, JMH) are a separate Maven profile (`mvn test -Pbenchmark`), not
part of this count.

---

## Schema Overview

```sql
saga_instance        -- lifecycle state, lease ownership, fencing token, JSONB context
saga_step_executions -- per-step-per-attempt: idempotency key, status, timeout_at, result
compensation_steps   -- compensation plan (written atomically at EXECUTING → COMPENSATING)
saga_entity_locks    -- business entity locks: (entity_type, entity_id) → saga_id
```

All four tables are created by Flyway migrations (V1–V4). Partial indexes on `lease_expires_at`
and `timeout_at` keep scanner queries fast under load.

---

## Metrics (Prometheus)

```
saga_executions_total{saga_type, status}          counter — terminal transitions
saga_step_duration_seconds{saga_type, step}        histogram — per-step latency
saga_compensations_total{saga_type, step}          counter — compensation step completions
saga_in_flight                                     gauge   — active EXECUTING sagas
saga_dead_letter_total{saga_type}                  counter — DEAD_LETTER transitions
```

Available at `/actuator/prometheus`. The WebSocket demo at `/` streams live step events
color-coded by status.

---

## Further Reading

- **[ARCHITECTURE.md](ARCHITECTURE.md)** — Every design decision explained in terms of the failure it prevents. Covers the write-ordering protocol, compensation atomicity, fencing tokens, timeout semantics, and lock ordering in full detail.
- **[PROJECT_STRUCTURE.md](PROJECT_STRUCTURE.md)** — Package-by-package breakdown of the codebase with dependency graph.
- **[BENCHMARK_RESULTS.md](BENCHMARK_RESULTS.md)** — JMH numbers with interpretation and production scaling notes.
- **[TODO.md](TODO.md)** — Phase-by-phase implementation log with key decisions and gotchas recorded per phase.
