# Project Structure

## Why This Structure

The package layout mirrors architectural responsibility boundaries, not technical layers.
Most Spring Boot projects organize by `controller/service/repository` — this project organizes by
*what problem each package solves*. This makes it easier to reason about correctness properties
in isolation and to explain any package's purpose without referencing others.

Cross-package dependencies flow in one direction: `engine` calls `infrastructure`, `persistence`,
and `event`. Nothing in `persistence` knows about `engine`.

---

## Root Layout

```
saga_orchestrator/
├── src/
│   ├── main/
│   │   ├── java/com/saga/orchestrator/
│   │   │   ├── definition/          ← DSL: step types, retry policy, saga builder
│   │   │   ├── engine/              ← orchestration core + step executors + compensation
│   │   │   ├── infrastructure/      ← locking, heartbeat, idempotency key generation
│   │   │   ├── persistence/         ← JPA entities + repositories
│   │   │   ├── scanning/            ← Quartz jobs: timeout, crash recovery, DLQ
│   │   │   ├── event/               ← event publishing, metrics listener, WebSocket listener
│   │   │   ├── web/                 ← REST controllers, request/response types, WebSocket config
│   │   │   ├── showcase/            ← 5-step fund-transfer saga demo
│   │   │   └── benchmark/           ← JMH benchmark classes
│   │   └── resources/
│   │       ├── application.yml
│   │       ├── static/index.html    ← WebSocket demo UI
│   │       └── db/migration/        ← Flyway SQL migrations V1–V4
│   └── test/
│       └── java/com/saga/orchestrator/
│           ├── definition/          ← DSL unit tests, idempotency key unit tests
│           ├── engine/              ← state machine unit tests
│           ├── integration/         ← full lifecycle + chaos + phase-by-phase tests
│           └── benchmark/           ← JMH benchmark runner test
├── ARCHITECTURE.md                  ← deep-dive on every design decision
├── BENCHMARK_RESULTS.md             ← JMH numbers with interpretation
├── PROJECT_STRUCTURE.md             ← this file
├── TODO.md                          ← phase-by-phase implementation log
├── docker-compose.yml               ← PostgreSQL 16 for local development
└── pom.xml
```

---

## Package-by-Package Breakdown

### `definition/` — The DSL

**What:** Fluent builder API for defining sagas. Contains step metadata, type classification,
retry policies, timeout configuration, and the validation logic that enforces ordering constraints.

**Why it exists:** The saga definition is the single source of truth for what a saga does.
By making this a first-class package, the DSL is testable in isolation (no Spring context,
no database) and validation rules are centralized rather than scattered across the engine.

**How it connects:** `SagaDefinitionRegistry` is a Spring bean that holds all registered
`SagaDefinition` instances. The engine looks up definitions by saga type name at runtime.

```
definition/
├── SagaDefinition.java            ← immutable record: type, ordered list of SagaStep
├── SagaDefinitionBuilder.java     ← fluent builder; throws SagaDefinitionException on bad ordering
├── SagaStep.java                  ← one step: name, type, action, compensation, retry, timeout
├── StepType.java                  ← enum: COMPENSATABLE | PIVOT | RETRYABLE
├── TimeoutMode.java               ← enum: RETRY_WITH_IDEMPOTENCY | TREAT_AS_FAILURE | WAIT_FOR_CALLBACK
├── RetryPolicy.java               ← exponential backoff config: maxAttempts, initialDelay, multiplier, jitter
├── ExecutionResult.java           ← sealed type: Success(result) | Failure(error) | TimedOut
├── StepAction.java                ← @FunctionalInterface: execute(SagaContext ctx, String idempotencyKey)
├── HttpStepAction.java            ← marker interface; only implementors may use WAIT_FOR_CALLBACK
├── SagaContext.java               ← marker interface for saga context types
├── SagaEvent.java                 ← enum: 11 orchestration events (STEP_SUCCEEDED, SAGA_COMPLETED, …)
├── SagaStatus.java                ← enum: 8 saga states; isTerminal() helper
├── StepExecutionStatus.java       ← enum: IN_PROGRESS | COMPLETED | FAILED | TIMED_OUT
├── CompensationStatus.java        ← enum: PENDING | IN_PROGRESS | COMPLETED | FAILED
├── EntityRef.java                 ← public API record: (entityType, entityId) for lock acquisition
├── EntityLockConflictException.java ← thrown on lock conflict; API layer maps to HTTP 409
├── SagaDefinitionRegistry.java    ← Spring bean: registers and looks up definitions by type name
└── SagaDefinitionException.java   ← thrown at build time for invalid step ordering
```

**Key invariant enforced here:** `COMPENSATABLE* → PIVOT? → RETRYABLE*`. The builder validates
this at `.build()` and throws at startup — not at runtime. `WAIT_FOR_CALLBACK` mode is
disallowed for non-`HttpStepAction` steps, also checked at build time.

---

### `engine/` — The Orchestration Core

**What:** The main entry points for starting a saga, advancing it, driving compensation, and
executing steps. All write-ordering protocol logic (persist intent → act → persist result) lives here.

**Why it exists:** Keeping orchestration logic in one place means the write-ordering protocol
is enforced in exactly one code path, not scattered across service classes.

**How it connects:** Calls into `infrastructure/`, `persistence/`, and `event/`. Receives
definitions from `definition/SagaDefinitionRegistry`.

```
engine/
├── SagaOrchestrator.java          ← public API: startSaga(), resumeSaga(), receiveCallback()
├── SagaInitializer.java           ← @Transactional: acquire locks → save saga → claim → track heartbeat
├── ForwardExecutionEngine.java    ← drives steps 0..N; handles retry, timeout routing, compensation trigger
├── CompensationEngine.java        ← drives compensations N..0 in reverse with per-step REQUIRES_NEW writes
├── CompensationPlanWriter.java    ← pure computation: builds List<CompensationStep> from definition
├── SagaStateMachine.java          ← pure function: (currentState, event) → nextState; no I/O
├── StepPersistenceService.java    ← all @Transactional(REQUIRES_NEW) DB writes for step lifecycle;
│                                     checks the fencing token before every state-mutating write
├── FencedOutException.java        ← thrown when a tracked fencing token no longer matches the
│                                     saga's current one; caught by ForwardExecutionEngine/CompensationEngine
├── TimeoutHandler.java            ← RETRY_WITH_IDEMPOTENCY retry; TREAT_AS_FAILURE compensation
├── StepActionExecutor.java        ← interface: execute(step, context, idempotencyKey, timeout)
├── BeanStepExecutor.java          ← wraps StepAction in CompletableFuture.get(timeout); virtual threads
├── HttpStepExecutor.java          ← RestClient call; injects X-Idempotency-Key header; maps exceptions
└── StepExecutorFactory.java       ← selects BeanStepExecutor or HttpStepExecutor by action type
```

**Key design decisions:**
- `SagaStateMachine` is a pure function — exhaustively unit-testable without infrastructure
- `StepPersistenceService` and `SagaInitializer` are separate beans to enable Spring `@Transactional`
  proxy interception (self-invocation would bypass the proxy)
- `REQUIRES_NEW` on persistence methods is intentional: intent MUST commit before action runs;
  result MUST commit independently so crash recovery can distinguish partial from full completion

---

### `infrastructure/` — Distributed Ownership and Idempotency

**What:** Three infrastructure concerns: which orchestrator node owns a saga (lease + heartbeat),
which saga holds an entity lock (business-level serialization), and deterministic idempotency key
generation.

**Why it exists:** These are correctness-critical shared services used by the engine. Grouping
them makes lock-related and key-generation logic findable and testable in isolation.

```
infrastructure/
├── SagaLockManager.java           ← claimSaga(), releaseSaga(), stealStaleSagas()
│                                     heartbeat_at + lease_duration detect stale owners
├── HeartbeatService.java          ← @Scheduled: renews lease on owned sagas every N seconds;
│                                     detects stolen leases via 0-rows-updated
├── EntityLockManager.java         ← acquireLocks() with canonical sort (deadlock prevention),
│                                     releaseLock(), releaseAllLocksForSaga()
└── IdempotencyKeyGenerator.java   ← SHA-256(sagaId + ":fwd:" + stepIndex + ":" + attempt)
                                      SHA-256(sagaId + ":comp:" + stepIndex + ":" + attempt)
```

**Key invariants:**
- Lock acquisition sorts `(entityType, entityId)` lexicographically before acquiring — all callers
  use the same order, so circular wait is structurally impossible
- `ON CONFLICT DO NOTHING` means competing sagas never block each other; conflict detected
  immediately via post-insert ownership check
- Fencing token incremented on every claim; stale-node writes rejected by an application-level
  check in `engine/StepPersistenceService` (`FencedOutException`), not a SQL `WHERE` clause —
  see ARCHITECTURE.md's "Fencing Tokens" section

---

### `persistence/` — Data Layer

**What:** JPA entities, Spring Data repositories, and Flyway migrations. All database access
goes through this package — no SQL in the engine or infrastructure.

```
persistence/
├── entity/
│   ├── SagaInstance.java          ← saga state, @Version (OCC), lock_owner, fencing_token
│   ├── SagaStepExecution.java     ← per-step-per-attempt: idempotency_key, status, timeout_at, result
│   ├── CompensationStep.java      ← compensation plan entry: step_index, action, key, status
│   └── SagaEntityLock.java        ← entity-level lock: (entity_type, entity_id) → saga_id
└── repository/
    ├── SagaInstanceRepository.java          ← findByStatusIn, claimSaga (native RETURNING)
    ├── SagaInstanceRepositoryCustom.java    ← custom interface for native queries
    ├── SagaInstanceRepositoryImpl.java      ← EntityManager native queries for lease ops
    ├── SagaStepExecutionRepository.java     ← findTimedOutSteps, findBySagaIdAndStepIndex…
    ├── CompensationStepRepository.java      ← findBySagaIdAndStatusIn…OrderByStepIndexDesc
    └── SagaEntityLockRepository.java        ← releaselock() native query with sagaId guard
```

**Key decisions:**
- No `@ManyToOne` on any entity — the engine queries each table directly; JPA relationships
  would enable accidental N+1 and lazy-load exceptions in the hot path
- `@Version Long version` on `SagaInstance` provides OCC for saga-state updates
- All timestamps use `TIMESTAMPTZ` (not `TIMESTAMP`) — correct behavior across timezones

---

### `scanning/` — Background Jobs

**What:** All Quartz-managed background jobs: timeout scanner, crash recovery scanner, and
DLQ reconciliation. Each job is split into a thin Quartz wrapper and a testable `@Component`
service so tests can inject the service directly without Quartz firing.

```
scanning/
├── TimeoutScanner.java            ← Quartz Job (every 5s): delegates to TimeoutScanService
├── TimeoutScanService.java        ← finds IN_PROGRESS steps past timeout_at; delegates to TimeoutHandler
├── OrphanedSagaScanner.java       ← Quartz Job (startNow + every 30s): delegates to OrphanedSagaScanService
├── OrphanedSagaScanService.java   ← finds expired-lease sagas; claims and resumes each via SagaOrchestrator
├── DlqReconciliationJob.java      ← Quartz Job (every 5 min): delegates to DlqReconciliationService
├── DlqReconciliationService.java  ← scans DEAD_LETTER sagas; emits structured log + metrics alert
└── QuartzJobsConfig.java          ← @Configuration: registers all job details and triggers
```

**Key design:** `spring.quartz.auto-startup=false` in test config prevents Quartz from firing
during integration tests. Tests call `scanService.scan()` directly, making test behavior
deterministic and independent of scheduling.

---

### `event/` — State Transition Events and Listeners

**What:** Every state transition emits a `SagaExecutionEvent`. Listeners include the Prometheus
metrics recorder and the WebSocket publisher. New listeners can be added without modifying the
engine — just implement `SagaEventListener`.

```
event/
├── SagaExecutionEvent.java        ← record: sagaId, sagaType, stepName, fromStatus, toStatus, timestamp, durationMs
├── SagaEventPublisher.java        ← synchronous dispatch to all SagaEventListener beans
├── SagaEventListener.java         ← interface: onEvent(SagaExecutionEvent)
├── SagaMetricsListener.java       ← Micrometer counters/timers: saga_executions_total,
│                                     saga_step_duration_seconds, saga_compensations_total,
│                                     saga_in_flight, saga_dead_letter_total
└── WebSocketEventListener.java    ← broadcasts event payload to /topic/sagas/{sagaId} via STOMP
```

---

### `web/` — REST API and WebSocket

**What:** HTTP interface for starting sagas, querying status, and receiving async callbacks.
WebSocket configuration for the live event stream.

```
web/
├── SagaController.java            ← POST /sagas (start), GET /sagas/{id} (status + step history)
│                                     @ExceptionHandler(EntityLockConflictException) → HTTP 409
├── CallbackController.java        ← POST /sagas/{sagaId}/callback/{stepIndex}?success=true|false
├── StartSagaRequest.java          ← record(sagaType, JsonNode context, List<EntityRef> lockedEntities)
├── SagaResponse.java              ← record(sagaId, sagaType, status, currentStep, createdAt, updatedAt, steps)
├── StepExecutionSummary.java      ← record(id, stepIndex, attemptNumber, idempotencyKey, status, …)
└── WebSocketConfig.java           ← STOMP over WebSocket at /ws with SockJS fallback; broker on /topic
```

---

### `showcase/` — Financial Transaction Saga Demo

**What:** A concrete 5-step financial workflow that exercises all seven correctness properties.
This is what runs in the demo and what gets described in interviews.

**The saga:** `credit-check → reserve-funds → debit-account → credit-beneficiary → send-notification`

```
showcase/
├── FundTransferSaga.java          ← SagaDefinition wired via DSL; registered via @PostConstruct
├── TransferContext.java           ← JSONB-serializable context: customerId, sourceAccountId,
│                                     destAccountId, amount, reservationId, authorizationId, transferId
├── CreditCheckService.java        ← COMPENSATABLE; checks balance; idempotent via ConcurrentHashMap
├── FundReservationService.java    ← COMPENSATABLE; reserves funds; compensation releases; idempotent
├── DebitService.java              ← PIVOT; implements HttpStepAction; WAIT_FOR_CALLBACK mode
├── CreditService.java             ← RETRYABLE; credits beneficiary; idempotent; sets transferId
└── NotificationService.java       ← RETRYABLE; sends confirmation; idempotent
```

**Behavior injection pattern:** Each participant exposes `setBehavior(Supplier<ExecutionResult>)`
and `reset()` for test control without modifying production logic. `AtomicReference` ensures
thread-safe behavior swaps.

---

### `benchmark/` — JMH Performance Benchmarks

**What:** JMH benchmarks for the four critical paths. Lives in `src/main/java` because the
JMH annotation processor requires classes on the compile classpath.

```
benchmark/
├── SagaBenchmark.java             ← @State(Scope.Benchmark): boots real Spring + Testcontainers;
│                                     sagaThroughput, sagaStepLatency, compensationLatency,
│                                     entityLockContention
└── BenchmarkRunner.java           ← main() for standalone CLI execution
```

Run via: `mvn test -Pbenchmark`

---

## Test Layout

```
test/java/com/saga/orchestrator/
├── definition/
│   ├── SagaDefinitionBuilderTest.java    ← 17 tests: valid/invalid orderings, WAIT_FOR_CALLBACK guard
│   ├── RetryPolicyTest.java              ← 10 tests: factories, exponential doubling, jitter bounds
│   ├── SagaDefinitionRegistryTest.java   ← 5 tests: register/lookup, duplicate detection
│   └── IdempotencyKeyGeneratorTest.java  ← same inputs → same key; separator collision test
├── engine/
│   ├── SagaStateMachineTest.java         ← 64 tests: all valid transitions + all invalid combinations
│   └── StaleWriterRejectedTest.java      ← fencing check rejects a stale node's write; new owner succeeds
├── integration/
│   ├── AbstractIntegrationTest.java      ← singleton Testcontainers PostgreSQL; Spring context cached
│   ├── AbstractWebIntegrationTest.java   ← WebEnvironment.MOCK + MockMvc; shares same container
│   ├── ApplicationSmokeTest.java         ← context loads, Flyway runs, 4 tables created
│   ├── PersistenceIntegrationTest.java   ← round-trip for all 4 entities; OCC; constraint tests
│   ├── Phase3IntegrationTest.java        ← lock acquire/release; deadlock prevention; heartbeat
│   ├── Phase4IntegrationTest.java        ← happy path; retry on failure; OCC on concurrent writes
│   ├── Phase5IntegrationTest.java        ← compensation in reverse; crash recovery; idempotency
│   ├── Phase6IntegrationTest.java        ← timeout modes; callback endpoint; orphaned saga recovery
│   ├── Phase7ControllerTest.java         ← REST API; entity lock 409; saga status endpoint
│   ├── Phase8IntegrationTest.java        ← Prometheus metrics counters/timers; DLQ alerting
│   ├── Phase8WebSocketTest.java          ← WebSocket events emitted per step
│   ├── CrashAtEachStepTest.java          ← crash at a step boundary (0..4) AND genuinely mid-step
│   │                                       (IN_PROGRESS row reused, not re-attempted) — scanner recovers all
│   ├── ConcurrentSagaSameAccountTest.java ← entity lock serialization under WAIT_FOR_CALLBACK
│   ├── CompensationUnderCrashTest.java   ← crash mid-compensation; scanner resumes correctly
│   └── IdempotencyUnder1000RetriesTest.java ← 1000 concurrent callers, same key → 1 execution
│                                             (proves the participant's cache, not the orchestrator's
│                                              write path — see ARCHITECTURE.md)
└── benchmark/
    └── BenchmarkSuiteTest.java           ← @Tag("benchmark"); runs JMH via Surefire
```

169 tests total — all pass via plain `mvn test`. Benchmarks are a separate Maven profile
(`mvn test -Pbenchmark`), not part of that count — see pom.xml's `benchmark` profile.

---

## Dependency Graph

```
web/ ──────────────────────────────────────────────────────────────┐
                                                                   ↓
showcase/ ──→ engine/ ──→ infrastructure/  ──→ persistence/entity/
                     ──→ persistence/repo/    persistence/repo/
                     ──→ event/           ──→ ↑
                              ↓
                         (metrics, websocket)

scanning/ ─────────────────────────────────→ persistence/repo/
                                          → engine/ (resumeSaga)

definition/ ← (no runtime dependencies; used at startup to build/register SagaDefinitions)
```
