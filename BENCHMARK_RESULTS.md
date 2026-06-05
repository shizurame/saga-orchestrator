# Benchmark Results

Measured with JMH 1.37 against a Testcontainers PostgreSQL 16-alpine on macOS (Apple M-series).
Single-threaded, `@Fork(0)`, 2 warmup iterations × 3 s + 3 measurement iterations × 3 s per benchmark.

**Environment:** MacBook Pro (Apple Silicon), Java 21.0.8, PostgreSQL 16-alpine (Docker/Testcontainers)

---

## Results

| Benchmark | Mode | Cnt | Score | Error | Unit |
|-----------|------|-----|-------|-------|------|
| `sagaStepLatency` — 1-step saga, orchestrator overhead only | avgt | 3 | **2,084** | ±598 | µs/op |
| `compensationLatency` — 2-step saga, step fails → full compensation | avgt | 3 | **4,957** | ±1,080 | µs/op |
| `sagaThroughput` — 5-step fund-transfer saga | thrpt | 3 | ~100 | — | ops/s |
| `entityLockContention` — per-account saga serialization | thrpt | 3 | ~100 | — | ops/s |

---

## Interpretation

### sagaStepLatency — 2.1ms/op
Time for a complete saga with one step: entity lock acquire → write intent → execute action → persist result → release lock. This measures pure orchestrator overhead. The bulk (~1.8ms) is 3 serialized DB round-trips over a loopback TCP connection to the Testcontainers container.

### compensationLatency — 5.0ms/op
Full compensation cycle from step failure to COMPENSATED terminal state:
- Step 0 executes (1 intent + 1 result write)
- Step 1 fails (1 intent + 1 failure write)
- Compensation plan written atomically with COMPENSATING status
- Step 0 compensation executes (1 intent + 1 result write)
- Lock release + COMPENSATED status written
Total: 8 DB writes across 4 REQUIRES_NEW transactions.

### sagaThroughput / entityLockContention — ~100 ops/s
100 complete 5-step sagas per second, single-threaded against containerized PostgreSQL. Each saga issues ~25 DB writes (5 intents + 5 results + 1 init + 1 terminal + lock ops). At ~100 ops/s and ~25 writes/op, the engine drives ~2,500 DB writes/s — consistent with PostgreSQL's synchronous commit overhead on loopback.

**Production context:** With a shared PostgreSQL connection pool, async saga dispatch, and multiple orchestrator threads, throughput scales horizontally. The Quartz JDBC store enables clustered deployment across nodes.

---

## Run Instructions

```bash
# Run benchmarks (excluded from normal mvn test)
mvn test -Dgroups=benchmark -Dnet.bytebuddy.experimental=true

# Results also written to benchmark-results.json (JMH JSON format)
```
