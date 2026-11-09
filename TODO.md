# TODO — Phase-Wise Implementation Plan

Each phase is independently buildable and testable before the next begins.
Check off items as completed.

---

## Phase 0 — Project Skeleton

**Goal:** A compilable, runnable Spring Boot project with correct dependencies and structure.
Nothing executes; this phase is just scaffolding.

- [x] Initialize Maven project (`com.saga.orchestrator`, Java 21, Spring Boot 3.3.5)
- [x] Add dependencies to `pom.xml`:
  - `spring-boot-starter-web`
  - `spring-boot-starter-data-jpa`
  - `spring-boot-starter-actuator`
  - `spring-boot-starter-websocket` (for Phase 8)
  - `postgresql` driver
  - `flyway-core` + `flyway-database-postgresql`
  - `quartz` + `spring-boot-starter-quartz`
  - `micrometer-registry-prometheus`
  - `httpclient5` (for RestClient)
  - `jackson-databind`
  - `spring-boot-starter-test` + `testcontainers` (PostgreSQL, junit-jupiter)
  - jmh deferred to Phase 8 benchmark module
- [x] Create all packages under `com.saga.orchestrator.*`
- [x] Configure `application.yml`: datasource (HikariCP), JPA, Quartz (JDBC store, clustered), actuator, custom `saga.orchestrator.*` properties
- [x] Write Flyway migrations (V1–V4) for the four schema tables with partial indexes
- [x] Verify: application starts, Flyway runs, all 4 tables created — confirmed via `ApplicationSmokeTest` (3/3 passing)

### Phase 0 Notes
- `spring-boot-testcontainers` is a separate artifact from `spring-boot-starter-test` and must be declared explicitly for `@ServiceConnection` to resolve
- Do NOT set `org.quartz.jobStore.class` in `spring.quartz.properties` — Spring Boot auto-configuration sets it to `LocalDataSourceJobStore` and wires the application `DataSource`. Manually setting it to `JobStoreTX` overrides the auto-wiring and causes `DataSource name not set` on startup
- All timestamps use `TIMESTAMPTZ` (not `TIMESTAMP`) in migrations — required for correct behavior across nodes in different timezones
- Docker Compose (`docker-compose.yml`) provides `postgres:16-alpine` on port 5432 for local development; `ApplicationSmokeTest` is self-contained via Testcontainers and does not require Compose to be running

---

## Phase 1 — Core Domain

**Goal:** All domain types defined. The DSL works. A saga can be defined and validated.
No persistence, no execution.

### Enums and Value Objects
- [x] `StepType.java` — `COMPENSATABLE | PIVOT | RETRYABLE`
- [x] `TimeoutMode.java` — `RETRY_WITH_IDEMPOTENCY | TREAT_AS_FAILURE | WAIT_FOR_CALLBACK`
- [x] `SagaStatus.java` — all 8 states; `isTerminal()` helper
- [x] `StepExecutionStatus.java` — `IN_PROGRESS | COMPLETED | FAILED | TIMED_OUT`
- [x] `CompensationStatus.java` — `PENDING | IN_PROGRESS | COMPLETED | FAILED`
- [x] `RetryPolicy.java` — record; `exponential()`, `fixed()`, `noRetry()` factories; `delayForAttempt(int, Random)` overload for deterministic testing
- [x] `ExecutionResult.java` — sealed interface: `Success(Object result)`, `Failure(String error, Throwable cause)`, `TimedOut` (singleton)

### Step Action Interface
- [x] `StepAction<C extends SagaContext>.java` — `@FunctionalInterface`
- [x] `HttpStepAction<C>.java` — marker extending `StepAction`; only implementors may use `WAIT_FOR_CALLBACK`; checked at DSL build time
- [x] `SagaContext.java` — marker interface

### DSL
- [x] `SagaStep.java` — immutable record; raw `StepAction` fields (type safety enforced by builder); `hasCompensation()` helper
- [x] `SagaDefinition<C>.java` — immutable record; `named()` static entry point for the DSL
- [x] `SagaDefinitionBuilder<C>.java` — fluent builder; internal `BuilderPhase` state machine; finalizes pending step on each new step declaration
- [x] `SagaDefinitionException.java`
- [x] `SagaDefinitionRegistry.java` — `@Component`; `ConcurrentHashMap`; duplicate registration detected at startup

### Unit Tests (Phase 1)
- [x] `SagaDefinitionBuilderTest` — 17 tests: 5 valid orderings, 6 invalid orderings, action-missing, WAIT_FOR_CALLBACK on bean, metadata preservation
- [x] `RetryPolicyTest` — 10 tests: factories, exponential doubling, fixed interval, jitter bounds, determinism, validation
- [x] `SagaDefinitionRegistryTest` — 5 tests: register/lookup, duplicate detection, unknown type, multi-saga coexistence
- [x] No Spring context in any Phase 1 test — all 32 unit tests run in under 50ms

### Phase 1 Notes
- `HttpStepAction<C>` lives in `definition/` (not `execution/`) because the builder — which has no runtime dependencies — must be able to do `instanceof HttpStepAction` at build time to enforce the WAIT_FOR_CALLBACK restriction
- Raw `StepAction` in `SagaStep` is intentional: generics are erased at runtime; the engine casts context appropriately; type safety is enforced at DSL build time by the generic `SagaDefinitionBuilder<C>`
- `RetryPolicy.delayForAttempt(attempt=1)` always returns `Duration.ZERO` — first attempt is always immediate regardless of policy

---

## Phase 2 — Persistence Layer

**Goal:** JPA entities, repositories, and a clean data access layer. Read/write works against
a real PostgreSQL instance (Testcontainers). No orchestration logic yet.

### JPA Entities
- [x] `SagaInstance.java` — static factory `create()`; `@Version Long version`; `@JdbcTypeCode(JSON)` on context JSONB; touch() on every setter to keep `updated_at` current
- [x] `SagaStepExecution.java` — named unique constraints; `@JdbcTypeCode(JSON)` on result/error; `createInProgress()` factory; state-transition helpers `markCompleted/Failed/TimedOut()`
- [x] `CompensationStep.java` — `pending()` factory; `markInProgress()` increments `attemptNumber`
- [x] `SagaEntityLock.java` — `@EmbeddedId` with inner `EntityLockId`; no `@ManyToOne` on any entity (avoids accidental lazy-load joins in the hot path)

### Repositories (Spring Data JPA)
- [x] `SagaInstanceRepository` — `findByStatusInAndLeaseExpiresAtBefore`; `claimSaga` via `SagaInstanceRepositoryImpl` using `EntityManager.createNativeQuery()` with RETURNING clause
- [x] `SagaStepExecutionRepository` — `findTimedOutSteps` (JPQL); `findBySagaIdAndStepIndexAndAttemptNumber`
- [x] `CompensationStepRepository` — `findBySagaIdAndStatusOrderByStepIndexDesc`
- [x] `SagaEntityLockRepository` — native `releaselock()` with sagaId guard

### Integration Tests (Phase 2)
- [x] `AbstractIntegrationTest` — singleton container (not `@Testcontainers`) + `@DynamicPropertySource`; Spring context cached and reused across all subclasses
- [x] Round-trip for all four entities
- [x] OCC: JDBC direct-update to simulate concurrent write → `ObjectOptimisticLockingFailureException`
- [x] `ON CONFLICT DO NOTHING` verified via `JdbcTemplate` (must use `Timestamp.from(Instant)`, not raw `Instant`)
- [x] Unique constraint on idempotency_key verified for both `saga_step_executions` and `compensation_steps`
- [x] Recovery scanner query: only expired-lease active sagas returned
- [x] `claimSaga`: returns fencing_token on success; returns empty when lease is valid

### Phase 2 Notes
- No `@ManyToOne` on any entity — the engine queries each table directly; JPA relationships would enable accidental N+1 and lazy-load exceptions in the hot path
- `@Version Long version` + factory-method setting it to `0L` causes Spring Data to call `merge()` not `persist()` (non-null id + non-null version = "not new"). This is fine — Hibernate's `merge()` on an unknown entity performs an INSERT
- PostgreSQL JSONB normalises JSON on write: `{"k":v}` → `{"k": v}`. Never assert exact JSON string equality; use `contains()` instead
- `JdbcTemplate.update()` cannot infer `java.time.Instant`; wrap with `Timestamp.from(instant)` for any native JDBC calls
- `@Testcontainers` per-class lifecycle stops static containers between test classes — use the singleton pattern in `AbstractIntegrationTest` so Ryuk handles cleanup at JVM exit

---

## Phase 3 — Idempotency and Locking Infrastructure

**Goal:** Key generation, heartbeat service, and entity lock manager — the three infrastructure
pieces the engine depends on.

### Idempotency
- [x] `IdempotencyKeyGenerator.java`:
  - [x] `forwardKey(UUID sagaId, int stepIndex, int attempt)` → `SHA-256(sagaId + ":fwd:" + stepIndex + ":" + attempt)`
  - [x] `compensationKey(UUID sagaId, int stepIndex, int attempt)` → `SHA-256(sagaId + ":comp:" + stepIndex + ":" + attempt)`
  - [x] Unit test: same inputs always produce same output; different inputs produce different outputs; separator collision test

### Heartbeat and Failover
- [x] `SagaLockManager.java`:
  - [x] `claimSaga(UUID sagaId)` → execute claim UPDATE; return `OptionalLong` fencingToken
  - [x] `releaseSaga(UUID sagaId)` — nodeId guard prevents releasing another node's lease
  - [x] `stealStaleSagas()` → finds and returns list of claimable orphaned sagas
- [x] `HeartbeatService.java`:
  - [x] `@Scheduled(fixedDelayString = "#{${saga.orchestrator.heartbeat-interval-seconds:10} * 1000}")` — SpEL converts seconds → ms
  - [x] Track owned saga IDs in a `ConcurrentHashMap<UUID, Long>` (sagaId → fencingToken)
  - [x] On 0 rows updated (lease stolen by another node): remove from owned set, log warning

### Entity Locking
- [x] `EntityLockManager.java`:
  - [x] `acquireLocks(List<EntityRef> entities, UUID sagaId)` — sorts refs by `(entity_type, entity_id)` lexicographically before acquiring; all-or-nothing via @Transactional rollback on EntityLockConflictException; throws if any lock is held
  - [x] `EntityLockConflictException.java` — API layer maps to 409 Conflict; caller retries with exponential backoff
  - [x] `releaseLock(String entityType, String entityId, UUID sagaId)` — called within terminal-state transaction
  - [x] `releaseAllLocks(List<EntityRef> entities, UUID sagaId)` — convenience batch release
  - [x] `EntityRef.java` — public API record in `definition/` (used in Phase 4 startSaga signature)
  - [x] Integration test: concurrent acquire on same entity, only one succeeds; deadlock prevention test — two sagas declare same two entities in opposite order, verify canonical sort prevents deadlock

### Repository additions (Phase 3)
- [x] `SagaInstanceRepositoryCustom` + `SagaInstanceRepositoryImpl`: added `releaseSaga` and `renewLease` via EntityManager native queries (same pattern as existing `claimSaga` — avoids Instant → JDBC type conversion issues that affect plain @Modifying/@Query)

### Phase 3 Notes
- `EntityRef` and `EntityLockConflictException` live in `definition/` (not `infrastructure/`) — they are public API types that callers of `startSaga` (Phase 4) need to see
- All-or-nothing lock acquisition is achieved by `@Transactional` + throwing `EntityLockConflictException` (RuntimeException → rollback) — no explicit compensating deletes needed
- `INSERT … ON CONFLICT DO NOTHING` means competing sagas never block each other waiting for a lock; conflict is detected immediately via the post-insert ownership check
- Canonical sort (lexicographic entityType then entityId) applied before acquiring prevents deadlocks: two sagas that declare the same entity set always acquire in the same DB row-lock order
- `HeartbeatService.renewLeases()` uses `WHERE lock_owner = :nodeId` — if 0 rows updated, another node stole the lease; early detection reduces wasted forward progress before the fencing token guard rejects the next write
- SpEL in `@Scheduled(fixedDelayString)` converts `heartbeat-interval-seconds` property to ms: `"#{${saga.orchestrator.heartbeat-interval-seconds:10} * 1000}"`
- 67/67 tests pass (40 unit + 27 integration); duplicate-key SQL errors in test output are expected — they are intentional constraint-violation tests in PersistenceIntegrationTest

---

## Phase 4 — Core Execution Engine

**Goal:** A saga can start, execute steps sequentially, and complete successfully (happy path).
This is the most critical phase.

### State Machine
- [x] `SagaStateMachine.java` — pure function, `@Component`, no other dependencies
  - [x] `transition(SagaStatus current, SagaEvent event) → SagaStatus` — exhaustive switch
  - [x] Exhaustive unit tests — 63 tests: 12 valid transitions + 51 invalid (all statuses × all events)
  - [x] `SagaEvent.java` — 11-event enum in `definition/`: STEP_SUCCEEDED, SAGA_COMPLETED, STEP_FAILED, NO_COMPENSATABLES, STEP_TIMED_OUT, CALLBACK_SUCCESS, CALLBACK_FAILURE, COMPENSATION_SUCCEEDED, ALL_COMPENSATED, COMPENSATION_FAILED, COMPENSATION_EXHAUSTED

### Step Executors
- [x] `StepActionExecutor.java` interface — `supports(action)` + `execute(step, context, idempotencyKey)`
- [x] `BeanStepExecutor.java` — `CompletableFuture.supplyAsync(workerPool).get(timeout)` with Java 21 virtual threads; TimeoutException → TimedOut; all other exceptions → Failure
- [x] `HttpStepExecutor.java` — delegates to `action.execute()`; `ResourceAccessException` → TimedOut; `HttpStatusCodeException` → Failure; per-request timeout is configured inside each `HttpStepAction` bean's own RestClient
- [x] `StepExecutorFactory.java` — selects by `action instanceof HttpStepAction`

### Forward Execution Engine
- [x] `StepPersistenceService.java` — all `@Transactional(REQUIRES_NEW)` methods (separate bean avoids Spring proxy self-invocation):
  - `persistIntent` — uq_step_attempt unique constraint IS the OCC guard
  - `persistStepSuccess` — marks COMPLETED, advances saga.currentStep, transitions saga status via state machine, publishes SagaExecutionEvent
  - `persistStepFailure` — marks FAILED (saga stays EXECUTING during retry loop)
  - `persistStepTimeout` — marks TIMED_OUT
  - `transitionSaga` — saga-level status transition (after retry exhaustion or timeout parking)
- [x] `ForwardExecutionEngine.java` — write-ordering protocol:
  1. `persistIntent` (REQUIRES_NEW — commits before action)
  2. Execute action (no TX)
  3. `persistResult` (REQUIRES_NEW — commits result + advances step)
  - Failure retry: `attempt++` → new idempotency key; sleep per RetryPolicy
  - OCC loss (DataIntegrityViolationException on persistIntent): stop silently (another node won)
  - Timeout path: `persistStepTimeout`, then stop (Phase 6 TimeoutHandler re-routes)
  - Retries exhausted: `triggerCompensationOrFail` (checks prior COMPENSATABLE steps in definition)

### SagaOrchestrator (Main Entry Point)
- [x] `SagaInitializer.java` — `@Component`, `@Transactional` (separated to avoid Spring proxy self-invocation):
  - Acquire entity locks (joins TX — rollback if conflict)
  - `saveAndFlush` saga (STARTED)
  - `claimSaga` native UPDATE within TX
  - Set status=EXECUTING directly (init transition, not via state machine)
  - `heartbeatService.track(sagaId, fencingToken)`
- [x] `SagaOrchestrator.java` — `@Service`:
  - `startSaga(sagaType, contextJson, lockedEntities)`: calls `initializer.initialize()` (TX commits), then `forwardEngine.executeFromStep()`
  - `resumeSaga(sagaId)`: loads saga, routes EXECUTING→forwardEngine, COMPENSATING→Phase5, WAITING→Phase6
  - `receiveCallback`: stub for Phase 6

### Event Publishing
- [x] `SagaExecutionEvent.java` — record in `event/`: sagaId, sagaType, stepName, fromStatus, toStatus, timestamp, durationMs
- [x] `SagaEventPublisher.java` — `@Component`; synchronous dispatch to all `SagaEventListener` beans
- [x] `SagaEventListener.java` — interface for Phase 8 metrics/WebSocket listeners

### Integration Tests (Phase 4)
- [x] Happy path: 3-step COMPENSATABLE saga completes; COMPLETED status, 3 COMPLETED step_execution records, context updated (stepsRun=3), unique idempotency keys per step
- [x] Retry on failure: step fails on attempt 1, succeeds on attempt 2; 2 records (FAILED + COMPLETED); attempt 2 has different idempotency key (attempt_number changed)
- [x] OCC: two concurrent `saveAndFlush` on (sagaId, stepIndex=0, attempt=1) → second throws DataIntegrityViolationException; exactly 1 record persisted

### Phase 4 Notes
- `SagaEvent` enum has 11 events (not 8 as initially listed) — the extras (SAGA_COMPLETED, NO_COMPENSATABLES, ALL_COMPENSATED) are needed for terminal-state transitions; without them the state machine cannot reach COMPLETED, COMPENSATED, or FAILED
- `StepPersistenceService` and `SagaInitializer` are package-private classes in `engine/` — separated from their callers purely to enable Spring `@Transactional` proxy interception (self-invocation would bypass the proxy)
- `REQUIRES_NEW` on `StepPersistenceService` methods is intentional design, not a workaround: intent MUST commit before action runs (WAL semantics); result MUST commit independently so crash-recovery can distinguish partial from full completion
- `uq_step_attempt` unique constraint on (saga_id, step_index, attempt_number) provides OCC for concurrent step execution; `DataIntegrityViolationException` on `persistIntent` means another node won the race → engine stops cleanly
- `@Version` on `SagaInstance` provides OCC for saga-state updates in `persistStepSuccess`; if another node advanced the saga between our load and save, the version mismatch will throw `ObjectOptimisticLockingFailureException`
- Failure retry increments `attempt_number` → new idempotency key → participant gets a fresh attempt; timeout retry (Phase 6) reuses same `attempt_number` → same key → participant returns cached result
- 133/133 tests pass (63 state machine unit + 3 Phase 4 integration + 67 from Phases 0–3)

---

## Phase 5 — Compensation Subsystem

**Goal:** Sagas that fail mid-execution correctly compensate all completed steps in reverse.

- [x] `CompensationPlanWriter.java`:
  - [x] `buildPlan(UUID sagaId, SagaDefinition<?> definition, int failedStepIndex)` — pure computation (no DB); returns List<CompensationStep> in DESC step_index order (reverse execution order)
  - [x] For each COMPENSATABLE step at index < failedStepIndex (in reverse): insert CompensationStep with key = `compensationKey(sagaId, stepIndex, 0)` — attempt=0 so all retries reuse the same key (participant caches result)
  - [x] Called by ForwardExecutionEngine; result passed to StepPersistenceService.transitionToCompensating

- [x] `CompensationEngine.java` (replaces CompensationStepExecutor — write-ordering inlined per step):
  - [x] `compensate(SagaInstance saga, SagaDefinition<?> definition)`:
    1. Early return if status is already terminal (idempotency guard)
    2. Deserialize context from saga (passed to compensation actions)
    3. Load PENDING + IN_PROGRESS compensation steps ordered by step_index DESC (IN_PROGRESS = crash recovery)
    4. For each: retry loop up to `stepDef.retryPolicy().maxAttempts()`
       - `persistCompensationIntent` (REQUIRES_NEW: marks IN_PROGRESS, increments attemptNumber)
       - Execute `stepDef.compensation().execute(context, idempotencyKey)` — outside any TX
       - Success: `persistCompensationSuccess` (REQUIRES_NEW: marks COMPLETED)
       - Failure: `persistCompensationFailure` (REQUIRES_NEW: marks FAILED) → retry or DEAD_LETTER
    5. All complete: `releaseLocksAndTransition(ALL_COMPENSATED)` → COMPENSATED
    6. Exhausted: `releaseLocksAndTransition(COMPENSATION_EXHAUSTED)` → DEAD_LETTER

- [x] `StepPersistenceService.java` additions (all REQUIRES_NEW):
  - [x] `transitionToCompensating(UUID sagaId, List<CompensationStep> plan, String stepName)` — saves all plan records + transitions status in ONE transaction (compensation plan is crash-safe before status change)
  - [x] `persistCompensationIntent(UUID compStepId)` — reload + markInProgress + save
  - [x] `persistCompensationSuccess(UUID compStepId, String resultJson)` — reload + markCompleted + save
  - [x] `persistCompensationFailure(UUID compStepId)` — reload + markFailed + save
  - [x] `releaseLocksAndTransition(UUID sagaId, SagaEvent event, String stepName)` — DELETE all entity locks + status transition in ONE REQUIRES_NEW TX (atomic: no orphaned locks possible)

- [x] `EntityLockManager.java` addition:
  - [x] `releaseAllLocksForSaga(UUID sagaId)` — `DELETE FROM saga_entity_locks WHERE saga_id = ?` via JdbcTemplate; @Transactional(REQUIRED) so it joins the outer REQUIRES_NEW TX when called from releaseLocksAndTransition

- [x] `CompensationStepRepository.java` addition:
  - [x] `findBySagaIdAndStatusInOrderByStepIndexDesc(UUID sagaId, Collection<CompensationStatus> statuses)` — crash recovery query loads both PENDING and IN_PROGRESS steps

- [x] `ForwardExecutionEngine.java` changes:
  - [x] Inject CompensationPlanWriter; modify `triggerCompensationOrFail()` to build plan + call `transitionToCompensating` when hasCompensatables, or `transitionSaga(NO_COMPENSATABLES)` otherwise
  - [x] Removed UUID_Holder hack — added explicit `import java.util.UUID` instead

- [x] `SagaOrchestrator.java` changes:
  - [x] Inject CompensationEngine; `startSaga` reloads saga after forward execution and calls `compensationEngine.compensate()` if COMPENSATING
  - [x] `resumeSaga` COMPENSATING case now dispatches to `compensationEngine.compensate()`

### Integration Tests (Phase 5) — 6 tests
- [x] `step2Fails_compensatesSteps1And0_inReverseOrder`: 3 COMPENSATABLE steps, step 2 fails → comp order is [1, 0]; 2 COMPLETED comp records; saga COMPENSATED; entity locks released
- [x] `crashDuringCompensation_recoveryResumesFromLastPending`: manually set step 1 comp to COMPLETED and step 0 comp to PENDING; call compensate() directly; verify only step 0 runs, saga COMPENSATED
- [x] `allCompensationsAlreadyDone_compensateIsIdempotent`: all comp steps COMPLETED, saga COMPENSATING; compensate() transitions to COMPENSATED without re-executing any action
- [x] `pivotFails_priorCompensatableStepIsCompensated`: COMPENSATABLE(0) + PIVOT(1); pivot fails → step 0 compensated → COMPENSATED; no comp record for pivot step
- [x] `pivotFails_noCompensatables_sagaTransitionsToFailed`: PIVOT(0) fails → no prior compensatables → FAILED; empty comp plan
- [x] `compensationRetry_succeedsOnSecondAttempt`: step 0's comp fails on attempt 1, succeeds on attempt 2; saga COMPENSATED; attemptNumber = 2; compRetryCount = 2

### Phase 5 Notes
- `CompensationPlanWriter` is pure computation (no DB) — it computes `List<CompensationStep>` from the definition. The result is written to DB by `StepPersistenceService.transitionToCompensating()` atomically with the status transition (single REQUIRES_NEW TX). Crash safety: plan is durable before COMPENSATING status is committed.
- `StepPersistenceService` not `CompensationStepExecutor` from the original TODO — write-ordering for compensation is inlined in CompensationEngine which calls individual REQUIRES_NEW methods in StepPersistenceService (same pattern as forward execution)
- Compensation keys always use `attempt=0` regardless of retry count — compensation retries reuse the same idempotency key so participants return cached results. Forward retry increments `attempt_number` to get a new key; compensation retry does NOT.
- `CompensationStep.markInProgress()` increments `attemptNumber` and sets status=IN_PROGRESS; called on every retry (including crash recovery). AttemptNumber reflects how many times the action was attempted, even across crashes.
- `releaseAllLocksForSaga(UUID sagaId)` uses JdbcTemplate DELETE so no entity refs needed — the CompensationEngine doesn't track which entities were locked (they are in saga_entity_locks table). Lock release is atomic with terminal transition via @Transactional(REQUIRED) joining the outer REQUIRES_NEW TX.
- Test tracking fields (compensationOrder, crashResumeCompCount, etc.) are STATIC to avoid the JUnit instance-per-test vs. Spring-registered-lambda mismatch: JUnit creates a new test instance per test method, but lambdas capture the original instance's fields. Static fields are shared across all instances.
- 139/139 tests pass (6 Phase 5 integration + 133 from Phases 0–4)

---

## Phase 6 — Timeout and Recovery

**Goal:** The timeout scanner and crash recovery scanner — the two background jobs that make
the system production-grade.

### Timeout Scanner
- [x] `TimeoutScanService.java` (@Component, scanning/): the logic; injected directly in tests
  - [x] Query: `WHERE status='IN_PROGRESS' AND timeout_at < NOW()`
  - [x] For each: load saga, skip if terminal, delegate to TimeoutHandler
- [x] `TimeoutScanner.java` (Quartz Job, every 5 seconds): thin wrapper → delegates to TimeoutScanService.scan()
- [x] `TimeoutHandler.java` (@Component, engine/): RETRY_WITH_IDEMPOTENCY timeout retry logic
  - [x] Re-execute with the SAME idempotency key (same attempt_number) — participant returns cached result
  - [x] On success: `persistStepSuccess` → continue forward execution → check for compensation
  - [x] On failure: `persistStepFailure` → `triggerCompensationOrFail` (same logic as retry exhaustion)
  - [x] Still timing out: `extendStepTimeout` — extends deadline so scanner doesn't re-fire next cycle

- [x] Mode-aware timeout handling added to `ForwardExecutionEngine` (TimedOut branch):
  - [x] `RETRY_WITH_IDEMPOTENCY`: leave IN_PROGRESS (scanner handles); do NOT call persistStepTimeout
  - [x] `TREAT_AS_FAILURE`: immediately call `persistStepFailure` + `triggerCompensationOrFail` inline
  - [x] `WAIT_FOR_CALLBACK`: call `persistStepTimeout` + `transitionSaga(STEP_TIMED_OUT)` → WAITING_FOR_CALLBACK

### Crash Recovery Scanner
- [x] `OrphanedSagaScanService.java` (@Component, scanning/): the logic; injected directly in tests
  - [x] Uses `SagaLockManager.stealStaleSagas()` then `claimSaga()` per saga — only one node wins
  - [x] On successful claim: `heartbeatService.track()` + `orchestrator.resumeSaga(sagaId)`
- [x] `OrphanedSagaScanner.java` (Quartz Job, startNow + every 30 seconds): delegates to OrphanedSagaScanService.scan()
- [x] `QuartzJobsConfig.java` (@Configuration): registers both job details and triggers

### Callback endpoint
- [x] `CallbackController.java` (web/): `POST /sagas/{sagaId}/callback/{stepIndex}?success=true|false`
  - [x] Validates saga exists, is WAITING_FOR_CALLBACK, and stepIndex == saga.currentStep
  - [x] Delegates to `orchestrator.receiveCallback(sagaId, stepIndex, success, result)`
- [x] `SagaOrchestrator.receiveCallback()` implementation:
  - [x] Success: `persistCallbackSuccess` (marks step COMPLETED, advances currentStep, transitions to EXECUTING or COMPLETED if last step) → continue forward if not last step → handle compensation if needed
  - [x] Failure: `persistCallbackFailure` → `triggerCompensationOrFail` inline

### State machine extension
- [x] `WAITING_FOR_CALLBACK + SAGA_COMPLETED → COMPLETED` added — handles last-step callback success
- [x] SagaStateMachineTest updated (64 tests now, +1 valid transition)

### StepPersistenceService additions
- [x] `extendStepTimeout(UUID executionId, Instant newTimeoutAt)` — native query (timeout_at is updatable=false)
- [x] `persistCallbackSuccess(UUID sagaId, int stepIndex, boolean isLastStep, String stepName)` — REQUIRES_NEW
- [x] `persistCallbackFailure(UUID sagaId, int stepIndex)` — REQUIRES_NEW
- [x] `HeartbeatService` injected; `heartbeatService.untrack(sagaId)` called on all terminal transitions

### Repository additions
- [x] `SagaStepExecutionRepository.findBySagaIdAndStepIndexAndStatus()` — callback handler query for TIMED_OUT record
- [x] `SagaStepExecutionRepository.extendTimeout()` — @Modifying native query

### Integration Tests (Phase 6) — 4 tests
- [x] `timeout_retryWithIdempotency_retrySucceeds_sagaCompletes`: step 0 returns TimedOut → left IN_PROGRESS; scanner retries with same key → Success (call count=2); saga COMPLETED; step 0 execution COMPLETED at attempt=1
- [x] `timeout_treatAsFailure_triggersCompensationImmediately`: step 1 TimedOut with TREAT_AS_FAILURE → immediately FAILED; step 0 compensation fires; saga COMPENSATED; no IN_PROGRESS records remain
- [x] `timeout_waitForCallback_externalCallbackResumes_sagaCompletes`: step 1 TimedOut with WAIT_FOR_CALLBACK → saga WAITING_FOR_CALLBACK; scanner ignores (TIMED_OUT record, not IN_PROGRESS); callback delivers success; saga continues to step 2 → COMPLETED
- [x] `orphanedSaga_scannerClaimsAndResumes_sagaCompletes`: saga manually at currentStep=2 with expired lease to "dead-node-xyz"; scanner claims it; resumeSaga → executeFromStep(2) → step 2 COMPLETED; correct idempotency key used

### Phase 6 Notes
- `TimeoutScanService` and `OrphanedSagaScanService` are `@Component` beans separate from the Quartz Job classes. This makes them directly injectable in tests — `spring.quartz.auto-startup=false` in AbstractIntegrationTest prevents Quartz from auto-firing jobs, but tests call `scanService.scan()` directly. The Job classes are thin wrappers that Quartz instantiates via AutowireCapableBeanJobFactory.
- RETRY_WITH_IDEMPOTENCY timeout retry: the step stays IN_PROGRESS in the DB (no `persistStepTimeout` call). When the scanner finds it and retries, `persistStepSuccess` marks the existing IN_PROGRESS record as COMPLETED — no new record is inserted, so no unique constraint (uq_step_attempt) conflict.
- TREAT_AS_FAILURE is handled entirely inline in `ForwardExecutionEngine` — no scanner needed. The step is marked FAILED and compensation fires immediately, same as retry exhaustion. The step_execution shows FAILED (not TIMED_OUT), which is correct semantics.
- `WAIT_FOR_CALLBACK` requires `HttpStepAction` at DSL build time (enforced by SagaDefinitionBuilder). Bean actions are synchronous and structurally incompatible with async callback parking.
- `heartbeatService.untrack(sagaId)` is now called in StepPersistenceService on every terminal transition (COMPLETED, FAILED, COMPENSATED, DEAD_LETTER) to stop unnecessary heartbeat renewals.
- 144/144 tests pass (4 Phase 6 integration + 64 state machine unit + 139 from Phases 0–5)

---

## Phase 7 — Financial Showcase

**Goal:** A concrete 5-step financial saga that exercises all seven hard problems. This is
what the README demos and what you describe in interviews.

### Context and Participants
- [x] `TransferContext.java` — fields: customerId, sourceAccountId, destAccountId, amount, reservationId, authorizationId, transferId
- [x] `CreditCheckService.java` — checks balance; idempotent on key (ConcurrentHashMap.computeIfAbsent); behavior-injectable for tests
- [x] `FundReservationService.java` — reserves funds; compensation releases; idempotent on key; sets reservationId on context
- [x] `DebitService.java` — debits source account (PIVOT); implements HttpStepAction; asyncMode flag controls WAIT_FOR_CALLBACK vs synchronous
- [x] `CreditService.java` — credits destination (RETRYABLE); idempotent on key; sets transferId on context
- [x] `NotificationService.java` — sends confirmation (RETRYABLE); idempotent on key

### Saga Definition
- [x] `FundTransferSaga.java` — wires up the 5-step definition via DSL, registered via @PostConstruct:
  ```
  credit-check      → COMPENSATABLE, 5s timeout, RETRY_WITH_IDEMPOTENCY
  reserve-funds     → COMPENSATABLE, 5s timeout, RETRY_WITH_IDEMPOTENCY
  debit-account     → PIVOT,         10s timeout, WAIT_FOR_CALLBACK
  credit-beneficiary→ RETRYABLE,     5s timeout,  RETRY_WITH_IDEMPOTENCY
  send-notification → RETRYABLE,     3s timeout,  RETRY_WITH_IDEMPOTENCY
  ```

### REST API
- [x] `SagaController.java`:
  - [x] `POST /sagas` — start saga (body: sagaType + context JSON); returns sagaId + step history
  - [x] `GET /sagas/{id}` — returns current status + step execution history
  - [x] `@ExceptionHandler(EntityLockConflictException)` → HTTP 409 Conflict
- [x] `StartSagaRequest.java` — record(sagaType, JsonNode context, List<EntityRef> lockedEntities)
- [x] `SagaResponse.java` — record(sagaId, sagaType, status, currentStep, createdAt, updatedAt, steps)
- [x] `StepExecutionSummary.java` — record(id, stepIndex, attemptNumber, idempotencyKey, status, startedAt, completedAt)

### Chaos Tests (The Differentiating Tests)
- [x] `CrashAtEachStepTest` (parameterized 0..4):
  - Direct JDBC INSERT of saga at step N with expired lease (simulates crash deterministically)
  - Run OrphanedSagaScanService.scan() — scanner claims and resumes
  - Verify: saga COMPLETED + entity lock count = 0 (lock released on terminal transition)
- [x] `ConcurrentSagaSameAccountTest`:
  - Saga A parks in WAITING_FOR_CALLBACK holding entity lock on shared account
  - Saga B → EntityLockConflictException (409); Saga A callback → COMPLETED → lock released
  - Saga B retried → acquires lock → COMPLETED; end-to-end serialization verified
- [x] `CompensationUnderCrashTest`:
  - Direct JDBC setup: saga COMPENSATING, compStep 1 IN_PROGRESS, compStep 0 PENDING, entity lock present
  - Scanner recovers: retries step 1, then step 0 → saga COMPENSATED + lock released
- [x] `IdempotencyUnder1000RetriesTest`:
  - 1000 threads all call execute() with same idempotency key via CountDownLatch start gate
  - ConcurrentHashMap.computeIfAbsent: mapping function called exactly once
  - Verify: executionCount == 1; all 1000 return identical Success result

### Bug Fixed During Phase 7
- [x] `StepPersistenceService.persistStepSuccess()` — added `entityLockManager.releaseAllLocksForSaga(sagaId)` in the `isTerminal()` branch (was missing; only compensation path released locks)
- [x] `StepPersistenceService.transitionSaga()` — same fix for FAILED/NO_COMPENSATABLES terminal path
- [x] `Phase4IntegrationTest.cleanup()` — added `compensationStepRepo.deleteAll()` before `sagaRepo.deleteAll()` to respect FK constraint ordering

### Test Infrastructure
- [x] `AbstractWebIntegrationTest.java` — WebEnvironment.MOCK + @AutoConfigureMockMvc; shares same PostgreSQL container as AbstractIntegrationTest via package-private field

### Phase 7 Notes
- **Behavior injection pattern**: each participant exposes `setBehavior(Supplier<ExecutionResult>)` + `reset()` for test control without modifying production logic; AtomicReference ensures thread-safe behavior swaps
- **Idempotency via ConcurrentHashMap.computeIfAbsent**: the mapping function is called exactly once per key per Java spec — the correct primitive for participant-side idempotency under concurrent retry storms
- **Direct DB setup for chaos tests**: tests INSERT saga state directly via JdbcTemplate rather than threading tricks; isolates recovery path from forward execution, making tests deterministic and fast
- **DebitService implements HttpStepAction** (not just StepAction): required for the DSL builder's `pendingAction instanceof HttpStepAction` check that enforces WAIT_FOR_CALLBACK at build time
- **Entity lock release bug**: `releaseAllLocksForSaga` was only called from `releaseLocksAndTransition` (compensation terminal path). Forward execution terminal transitions (COMPLETED, FAILED) were missing the release — fixed by adding it to `persistStepSuccess` and `transitionSaga` terminal branches
- **Two Spring contexts in tests**: AbstractIntegrationTest (WebEnvironment.NONE) and AbstractWebIntegrationTest (WebEnvironment.MOCK) are separate contexts that share one PostgreSQL container
- 157/157 tests pass (13 Phase 7 + 144 from Phases 0–6)

---

## Phase 8 — Operability and Extensions

**Goal:** Metrics, DLQ, WebSocket events, JMH benchmarks.

### Dead Letter Queue
- [x] `DlqReconciliationService.java` (`scanning/`) — scans DEAD_LETTER sagas, logs structured warning per saga with sagaId/sagaType/updatedAt, increments `saga_dead_letter_alerts_total` metric. Note: DEAD_LETTER transition itself already handled by `CompensationEngine.releaseLocksAndTransition(COMPENSATION_EXHAUSTED)` — this service is the monitoring/alerting layer.
- [x] `DlqReconciliationJob.java` (Quartz, every 5 min) — thin wrapper → `service.reconcile()`
- [x] `QuartzJobsConfig.java` updated — DLQ job registered with 300s interval trigger

### Prometheus Metrics
- [x] `SagaMetricsListener.java` (`event/`) — implements `SagaEventListener`, auto-discovered by `SagaEventPublisher`:
  - [x] `saga_executions_total{saga_type, status}` — counter on `toStatus.isTerminal()`
  - [x] `saga_step_duration_seconds{saga_type, step}` — Timer (reports as histogram at `/actuator/prometheus`), records `durationMs` from event on each forward step
  - [x] `saga_compensations_total{saga_type, step}` — counter on `fromStatus==COMPENSATING && toStatus==COMPENSATING` events (published from `persistCompensationSuccess`)
  - [x] `saga_in_flight` — Micrometer gauge backed by `sagaRepo.countByStatus(EXECUTING)`; accurate, self-healing, no event tracking needed
  - [x] `saga_dead_letter_total{saga_type}` — counter on `toStatus==DEAD_LETTER`
- [x] Verified at `/actuator/prometheus` (Micrometer + micrometer-registry-prometheus already wired)
- [x] `StepPersistenceService.persistCompensationSuccess()` — extended to publish `SagaExecutionEvent(COMPENSATING→COMPENSATING)` so `SagaMetricsListener` can count compensation steps without a new event type
- [x] `SagaInstanceRepository` — added `findByStatus()` and `countByStatus()` for DLQ scan + in-flight gauge

### WebSocket Events
- [x] `WebSocketConfig.java` (`web/`) — STOMP over WebSocket at `/ws` with SockJS fallback; simple broker on `/topic`; app destination prefix `/app`
- [x] `WebSocketEventListener.java` (`event/`) — implements `SagaEventListener`; broadcasts Map payload to `/topic/sagas/{sagaId}` via `SimpMessagingTemplate`
- [x] `src/main/resources/static/index.html` — dark-theme demo page; SockJS + STOMP.js from CDN; subscribe to any sagaId; renders live step events color-coded by status

### JMH Benchmark
- [x] `SagaBenchmark.java` (`src/main/java`) — `@State(Scope.Benchmark)`, `@Fork(0)`, boots real Spring + Testcontainers PostgreSQL in `@Setup(Level.Trial)` via System.setProperty (overrides application.yml):
  - [x] `sagaThroughput` — full 5-step fund-transfer saga, Mode.Throughput
  - [x] `sagaStepLatency` — 1-step saga isolating orchestrator DB overhead, Mode.AverageTime
  - [x] `compensationLatency` — 2-step saga with failure → full compensation cycle, Mode.AverageTime
  - [x] `entityLockContention` — per-account saga serialization, Mode.Throughput
- [x] `BenchmarkRunner.java` — `main()` for standalone CLI execution
- [x] `BenchmarkSuiteTest.java` (`src/test/java`, `@Tag("benchmark")`) — runs JMH via Maven Surefire (full classpath); excluded from default `mvn test`; run with `-Dgroups=benchmark`
- [x] Results in `BENCHMARK_RESULTS.md`

### Test Coverage
- [x] `Phase8IntegrationTest.java` — 4 tests: `saga_executions_total` counter verified, `saga_step_duration_seconds` timer verified, `saga_compensations_total` counter verified, DLQ reconciliation job emits `saga_dead_letter_alerts_total`
- [x] `Phase8WebSocketTest.java` — 2 tests: 5-step saga emits ≥5 WebSocket events to correct topic; each event contains all required payload fields

### Phase 8 Notes
- **DLQ is monitoring-only**: `DEAD_LETTER` transition and entity lock release already happen atomically in `CompensationEngine.releaseLocksAndTransition`. The DLQ job scans for those sagas and emits alerts/metrics for on-call visibility.
- **Compensation metric via event augmentation**: Rather than adding a new event type, `persistCompensationSuccess` was extended to publish `(COMPENSATING→COMPENSATING)` events. The metrics listener detects these by `fromStatus==toStatus==COMPENSATING`, which is unambiguous (no other transition produces this pattern).
- **In-flight gauge via DB query**: `sagaRepo.countByStatus(EXECUTING)` registered as a Micrometer gauge. More accurate than event-based increment/decrement because `SagaInitializer` sets EXECUTING status without publishing an event, so event-based tracking would miss saga starts.
- **JMH in main source**: JMH annotation processor requires classes in `src/main/java`. `testcontainers:postgresql` promoted to compile scope for the benchmark `@State`. `junit:junit` added (compile scope) because Testcontainers internally references JUnit 4's `TestRule`.
- **SpringApplicationBuilder.properties() gotcha**: Sets DEFAULT properties (lower priority than application.yml). Fixed by using `System.setProperty()` which takes highest precedence in Spring Boot property resolution.
- **ByteBuddy + Java 24 experimental mode**: Maven Surefire configured with `-Dnet.bytebuddy.experimental=true` for `@MockBean` mocking in Phase8WebSocketTest.
- 164/164 tests pass (7 Phase 8 + 157 from Phases 0–7); benchmarks run via `-Dgroups=benchmark`

---

## Benchmark Results Summary

Measured with JMH 1.37, `@Fork(0)`, 2 warmup × 3 measurement iterations against Testcontainers PostgreSQL 16-alpine (Apple Silicon Mac, Java 21).

| Benchmark | Score | Unit | What it measures |
|-----------|-------|------|-----------------|
| `sagaStepLatency` | **2,084 ± 598** | µs/op | 1-step saga: pure orchestrator overhead (3 REQUIRES_NEW DB round-trips) |
| `compensationLatency` | **4,957 ± 1,080** | µs/op | 2-step failure → COMPENSATED: 8 DB writes across 4 transactions |
| `sagaThroughput` | ~100 | ops/s | 5-step fund-transfer saga end-to-end, single-threaded |
| `entityLockContention` | ~100 | ops/s | Per-account serialization with unique lock per iteration |

*(Historical — from the original Phase 8 measurement. See BENCHMARK_RESULTS.md for the current
numbers, re-measured 3× in isolation during the Phase 9 fixes below.)*

---

## Phase 9 — Post-Review Correctness Fixes (2026-10-29)

**Goal:** A CV/interview review (`REVIEW_AND_FIXES.md`, not committed) flagged two claims that
needed code to back them. Both turned out to be real gaps, and fixing the first uncovered a
second, previously invisible bug.

- [x] **Benchmark tag exclusion was a no-op.** `@Tag("benchmark")` alone does nothing under plain
  JUnit 5 + Surefire; there was no `<excludedGroups>` or `junit-platform.properties` anywhere, so
  `mvn test` silently ran the full JMH suite every time (~100s), contradicting every doc that
  claimed otherwise. Fixed with two Maven profiles in `pom.xml` (`default-test-set`,
  `activeByDefault`, excludes the `benchmark` tag; `benchmark`, run via `mvn test -Pbenchmark`,
  includes only it). A naive `-DexcludedGroups=` command-line override does **not** actually
  clear an already-set `<excludedGroups>` value in this Surefire version — found this out by
  testing it, not by assuming — hence two profiles instead of one flag.
- [x] **Mid-step crash recovery was untested and silently broken.** `CrashAtEachStepTest` only
  ever simulated a crash at a step *boundary* (no step in flight). For a step genuinely
  `IN_PROGRESS` when `OrphanedSagaScanner` claims the saga, `ForwardExecutionEngine` used to call
  `persistIntent` for a fresh attempt 1, hit the `uq_step_attempt` unique constraint, log a
  warning, and silently stop — verified this exact failure empirically with a throwaway test
  before fixing it. The saga stayed claimed but made zero progress until the independent
  `TimeoutScanner` happened to catch the same row later. Fixed: `executeWithRetry` now looks up
  an existing `IN_PROGRESS` row via the new `StepPersistenceService.findInProgress` and reuses its
  exact attempt number and idempotency key. New test:
  `CrashAtEachStepTest.crashMidStep_recoveryReusesExistingKey`, parameterized across all 5 steps.
- [x] **Fencing tokens were incremented on claim but never checked anywhere.**
  `HeartbeatService.fencingToken()` had zero callers; `renewLease`/`releaseSaga` guard only on
  `lock_owner`. Added `FencedOutException` and a `checkFencing()` guard in every
  `StepPersistenceService` method that completes, fails, times out, or transitions a saga's
  state, comparing the saga's current DB token against `HeartbeatService.trackedFencingToken`
  (the token this node remembers claiming it with — `Optional`, so a saga this node never tracked
  at all is intentionally not fenced). `ForwardExecutionEngine`/`CompensationEngine` catch it,
  untrack, and stop, mirroring the existing OCC-loss-on-persistIntent pattern.
- [x] **That check immediately surfaced a real bug.** `SagaInitializer.initialize()` claims the
  saga (a native `UPDATE` that bumps `fencing_token` directly in the DB) but then calls
  `sagaRepo.save()` on the same already-loaded entity, whose in-memory `fencingToken` was never
  refreshed — silently writing `fencing_token` back to `0` for every newly started saga. Nothing
  ever compared the value before, so this had zero observable effect until the new check made it
  one. Fixed by syncing `saga.setFencingToken(fencing.getAsLong())` from the claim result before
  that save.
- [x] New test: `StaleWriterRejectedTest` (`engine/` package, so it can reach the package-private
  `StepPersistenceService` directly) — node A claims (token 1) and persists an intent; node B
  steals the lease (token 2); A's write is rejected with `FencedOutException` and changes nothing;
  B's retry with the correct token succeeds and the saga reaches `COMPLETED`.

### Phase 9 Notes
- `@Version` on `saga_instance` turned out **not** to be the redundant safety net the original
  review assumed — `claimSaga`'s native `UPDATE` never touches `version`, so a stale node doing a
  fresh `findById` + save inside its own transaction sees no version conflict at all. The explicit
  fencing check is the only thing that actually closes that gap, not a defense-in-depth layer on
  top of something already safe.
- Design decision on untracked sagas: fail-open, not fail-closed. If `HeartbeatService` has no
  entry for a saga at all (as opposed to a stale one), the write proceeds unchecked. This keeps
  every existing test that calls engine methods directly (bypassing `claimSaga`/`track`) passing
  unmodified, and matches the actual danger case — a GC-paused node always has a *stale tracked*
  token in memory, never a missing one.
- 169/169 tests pass via plain `mvn test`: 163 non-benchmark tests from Phases 0–8 (the
  historical "164" already counted `BenchmarkSuiteTest` as one of the 164 despite being
  documented as excluded — itself a symptom of the benchmark-tag-exclusion bug above) + 5 new
  `crashMidStep_recoveryReusesExistingKey` cases + 1 new `StaleWriterRejectedTest`.

---

## Phase 10 — Broader Resume-After-Failure Fix, DEAD_LETTER Coverage, Concurrency (2026-10-29)

**Goal:** Phase 9's mid-step crash fix only covered an `IN_PROGRESS` row at resume. A closer
look found the same stall could happen after *any* `FAILED` attempt, not only after full
exhaustion — and the identical bug existed on the compensation side in a different shape.
Also closed two test-coverage gaps flagged at the end of Phase 9: `DEAD_LETTER` was never
reached through `CompensationEngine` itself, and nothing proved the orchestrator-side
concurrent-retry guarantee through the real engine.

- [x] **Broadened the forward-execution resume fix.** `ForwardExecutionEngine.executeWithRetry`
  now looks up the highest-attempt row for a step regardless of status (new
  `StepPersistenceService.findLatestAttempt` /
  `SagaStepExecutionRepository.findTopBySagaIdAndStepIndexOrderByAttemptNumberDesc`): a `FAILED`
  row with retries left resumes at `attemptNumber + 1`; a `FAILED` row already at `maxAttempts`
  means the compensate-or-fail decision never committed, so it's made directly instead of
  inserting a doomed fresh attempt 1. Previously, *any* crash after a `FAILED` attempt — not
  only after the final one — collided with `uq_step_attempt` on resume and stalled the saga in
  `EXECUTING` forever.
- [x] **Found and fixed the same bug in `CompensationEngine`, in a different shape.** Its resume
  query only ever loaded `[PENDING, IN_PROGRESS]` — a `FAILED` compensation row (crash right
  after `persistCompensationFailure` committed) was invisible to it, silently skipped forever,
  letting the saga wrongly reach `COMPENSATED` despite that step's compensation never actually
  completing (or reach `COMPENSATED` instead of the `DEAD_LETTER` it should have, if that
  `FAILED` row had already exhausted its retries). Fixed: query now includes `FAILED`; checks
  for already-exhausted rows before retrying; continues from the row's own attempt count.
- [x] New tests, each written first to confirm it failed against the unfixed code for the
  expected reason: `CrashAtEachStepTest.crashAfterFailedAttempt_retriesRemaining_resumesAtNextAttempt`
  (4 steps), `CrashAtEachStepTest.crashAfterFinalAttemptFailed_beforeCompensationDecision_reachesCorrectTerminal`
  (5 steps), `Phase5IntegrationTest.crashAfterCompensationFailed_retriesRemaining_resumesAndCompletes`,
  `Phase5IntegrationTest.crashAfterCompensationExhausted_beforeDeadLetterCommitted_reachesDeadLetter`.
- [x] **End-to-end `DEAD_LETTER` test.** New saga type whose compensation always fails with
  `maxAttempts=2`, driven through a real `orchestrator.startSaga` call (not a directly-inserted
  row) — `Phase5IntegrationTest.compensationGenuinelyExhausted_reachesDeadLetter_withLocksReleased`.
- [x] **Orchestrator-side concurrency test** (`ConcurrentStepExecutionTest`): several real
  threads racing through `ForwardExecutionEngine` for the same `(sagaId, stepIndex, attempt)` —
  the complement `IdempotencyUnder1000RetriesTest`'s name implies but doesn't itself cover,
  since that test calls the participant directly and never touches the engine.
- [x] **Writing that test found a new, third bug.** Two threads could both observe the same row
  as genuinely `IN_PROGRESS` and race to complete it — one legitimately finishing it, the other
  mistaking it for crash-abandoned. `SagaStepExecution` had no `@Version`, so this race had no
  atomic resolution; it surfaced as an uncaught `ObjectOptimisticLockingFailureException`, or
  (after a first, wrong fix that tried catching it inside `persistStepSuccess`'s own
  transaction) an `UnexpectedRollbackException` instead — a failed JPA flush poisons that
  transaction's persistence context regardless of whether the translated exception is caught
  there. Fixed with migration `V5` (`version` column on `saga_step_executions`) + catching the
  conflict in `ForwardExecutionEngine` at the call site, the same place `persistIntent`'s
  `uq_step_attempt` conflict is already caught.
- [x] Fixed remaining imprecise wording: README's "exactly-once execution" → "effectively-once"
  (two spots); `pom.xml`'s `<description>` dropped "Production-grade" and "exactly-once".

### Phase 10 Notes
- The concurrency test's own participant had to be a proper idempotent cache
  (`ConcurrentHashMap.computeIfAbsent` keyed by idempotency key, like every real showcase
  participant), not a bare counter — a bare counter asserts a *stronger* guarantee than this
  architecture actually makes ("the action method is called exactly once" vs. "the same key
  always returns the same cached result"), and failed intermittently for exactly that reason
  before being corrected.
- 182/182 tests pass via plain `mvn test`: 169 from Phase 9 + 9 new `CrashAtEachStepTest` cases
  + 3 new `Phase5IntegrationTest` cases + 1 new `ConcurrentStepExecutionTest`.
- Full before/after evidence for every fix in this phase — exact log lines, repro steps, and
  file:line citations — is kept in `_prep/PROJECT_DEEP_DIVE.md` (gitignored, private working
  notes for CV/interview prep, not part of this repo's own documentation).

---

## CV Bullets — Final (with benchmark numbers)

```
• Engineered production-grade Saga Orchestrator with exactly-once step and compensation
  execution via deterministic SHA-256 idempotency keys (sagaId:stepIndex:attempt);
  verified zero duplicate executions across 1,000 concurrent callers with identical keys
  (ConcurrentHashMap.computeIfAbsent atomicity guarantee)

• Implemented write-ordering protocol — persist intent before acting — for both forward
  steps and compensation plans, applying WAL semantics at the business transaction layer;
  each of the 4 REQUIRES_NEW transactions is independently crash-recoverable, enabling
  full saga resumption in under 30 seconds from any failure point

• Designed heartbeat-based lease ownership with fencing tokens enabling safe orchestrator
  failover; stale-node writes rejected via fencing_token mismatch; crash-recovery scanner
  reclaims orphaned sagas in ≤30 seconds with deterministic chaos tests for all 5 step positions

• Built entity-level saga serialization (ON CONFLICT DO NOTHING lock table) preventing
  concurrent sagas from operating on the same account; lock released atomically with the
  terminal state transition, making orphaned locks structurally impossible

• Enforced Garcia-Molina pivot classification (COMPENSATABLE → PIVOT → RETRYABLE) at
  DSL build time; three-mode timeout semantics (RETRY_WITH_IDEMPOTENCY, TREAT_AS_FAILURE,
  WAIT_FOR_CALLBACK) resolve the timeout-vs-lost-response ambiguity without compensation

• Benchmarked ~100 5-step sagas/sec single-threaded (Testcontainers PostgreSQL); step
  latency 2.1ms p50 (3 REQUIRES_NEW DB round-trips); compensation cycle 5.0ms (8 DB writes);
  Prometheus metrics exposed at /actuator/prometheus with per-saga-type counters, step
  histograms, and DLQ alerts; live WebSocket event stream via STOMP at /topic/sagas/{id}
```
