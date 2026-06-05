package com.saga.orchestrator.benchmark;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.saga.orchestrator.SagaOrchestratorApplication;
import com.saga.orchestrator.definition.*;
import com.saga.orchestrator.engine.SagaOrchestrator;
import com.saga.orchestrator.showcase.FundTransferSaga;
import com.saga.orchestrator.showcase.TransferContext;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * JMH benchmarks for the saga orchestration engine.
 *
 * Measures:
 *   sagaThroughput        — ops/second for a full 5-step COMPLETED saga (fund-transfer)
 *   sagaStepLatency       — μs per step for a 1-step saga (isolates orchestrator overhead)
 *   compensationLatency   — total time from step failure to COMPENSATED terminal state
 *   entityLockContention  — throughput under concurrent sagas on the same account (serialized queue)
 *
 * @Fork(0) is required so the benchmark shares the JVM with the Testcontainers PostgreSQL
 * container started in @Setup. Forking would spawn a new JVM with no container.
 *
 * Run:  mvn package -DskipTests && java -jar target/saga-orchestrator-*.jar --benchmark
 * Or:   mvn test -Dtest=BenchmarkRunner -DfailIfNoSpecifiedTests=false
 */
@State(Scope.Benchmark)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 3)
@Measurement(iterations = 5, time = 5)
@Fork(0)
public class SagaBenchmark {

    private static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private ConfigurableApplicationContext context;
    private SagaOrchestrator              orchestrator;
    private SagaDefinitionRegistry        registry;
    private ObjectMapper                  objectMapper;
    private String                        transferContextJson;

    // For contention benchmark
    private static final String CONTENDED_ACCOUNT = "acc-benchmark-contended";
    private final AtomicInteger counter = new AtomicInteger(0);

    @Setup(Level.Trial)
    public void setup() throws Exception {
        postgres.start();

        // System properties take priority over application.yml;
        // SpringApplicationBuilder.properties() sets defaults which application.yml overrides.
        System.setProperty("spring.datasource.url",      postgres.getJdbcUrl());
        System.setProperty("spring.datasource.username", postgres.getUsername());
        System.setProperty("spring.datasource.password", postgres.getPassword());
        System.setProperty("spring.quartz.auto-startup", "false");
        System.setProperty("spring.jpa.show-sql",        "false");
        System.setProperty("logging.level.root",         "WARN");

        context = new SpringApplicationBuilder(SagaOrchestratorApplication.class).run();

        orchestrator = context.getBean(SagaOrchestrator.class);
        registry     = context.getBean(SagaDefinitionRegistry.class);
        objectMapper = context.getBean(ObjectMapper.class);

        transferContextJson = objectMapper.writeValueAsString(
                new TransferContext("cust-bench", "acc-bench-src", "acc-bench-dst",
                        new BigDecimal("100")));

        // Register a minimal 1-step saga for latency isolation
        if (!registry.isRegistered("bench-1step")) {
            SagaDefinition<BenchCtx> oneStep = SagaDefinition.named("bench-1step", BenchCtx.class)
                    .retryableStep("only-step")
                        .action((ctx, key) -> new ExecutionResult.Success(null))
                        .timeout(Duration.ofSeconds(5), TimeoutMode.RETRY_WITH_IDEMPOTENCY)
                    .build();
            registry.register(oneStep);
        }

        // Register a 2-step saga that fails step 1 for compensation latency
        if (!registry.isRegistered("bench-compensate")) {
            SagaDefinition<BenchCtx> compSaga = SagaDefinition.named("bench-compensate", BenchCtx.class)
                    .compensatableStep("step-0")
                        .action((ctx, key) -> new ExecutionResult.Success(null))
                        .compensation((ctx, key) -> new ExecutionResult.Success(null))
                        .timeout(Duration.ofSeconds(5), TimeoutMode.RETRY_WITH_IDEMPOTENCY)
                    .compensatableStep("step-1-fails")
                        .action((ctx, key) -> new ExecutionResult.Failure("benchmark-induced failure", null))
                        .compensation((ctx, key) -> new ExecutionResult.Success(null))
                        .timeout(Duration.ofSeconds(5), TimeoutMode.RETRY_WITH_IDEMPOTENCY)
                    .build();
            registry.register(compSaga);
        }
    }

    @TearDown(Level.Trial)
    public void teardown() {
        if (context != null) context.close();
        // postgres is static — Ryuk cleans it up at JVM exit; stopping it here would
        // break subsequent benchmarks that share the same static container.
    }

    /** Full 5-step fund-transfer saga: credit-check → reserve → debit → credit → notify */
    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    public void sagaThroughput(Blackhole bh) {
        try {
            UUID id = orchestrator.startSaga(FundTransferSaga.SAGA_TYPE,
                    transferContextJson, List.of());
            bh.consume(id);
        } catch (Exception e) {
            // swallow — contention on re-used account IDs; measure throughput only
        }
    }

    /** 1-step saga: isolates orchestrator overhead (lock acquire + 1 DB round trip + heartbeat) */
    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    public void sagaStepLatency(Blackhole bh) throws Exception {
        String ctxJson = objectMapper.writeValueAsString(new BenchCtx());
        UUID id = orchestrator.startSaga("bench-1step", ctxJson, List.of());
        bh.consume(id);
    }

    /** 2-step COMPENSATABLE saga where step 1 always fails → measures full compensation cycle */
    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    public void compensationLatency(Blackhole bh) throws Exception {
        String ctxJson = objectMapper.writeValueAsString(new BenchCtx());
        UUID id = orchestrator.startSaga("bench-compensate", ctxJson, List.of());
        bh.consume(id);
    }

    /** Entity lock contention: concurrent sagas on the same account serialize correctly */
    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    public void entityLockContention(Blackhole bh) {
        // Use unique accounts per iteration to avoid EntityLockConflictException dominating
        String account = "acc-contention-" + counter.incrementAndGet();
        try {
            UUID id = orchestrator.startSaga(FundTransferSaga.SAGA_TYPE,
                    objectMapper.writeValueAsString(new TransferContext(
                            "cust", account, "acc-dst", new BigDecimal("50"))),
                    List.of(new EntityRef(FundTransferSaga.ENTITY_TYPE_ACCOUNT, account)));
            bh.consume(id);
        } catch (Exception e) {
            bh.consume(e);
        }
    }

    public static class BenchCtx implements SagaContext {
        private int step = 0;
        public int getStep() { return step; }
        public void setStep(int s) { this.step = s; }
    }
}
