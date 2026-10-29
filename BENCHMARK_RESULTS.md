# Benchmark Results

Measured with JMH 1.37 against a Testcontainers PostgreSQL 16-alpine on macOS (Apple Silicon).
Single-threaded, `@Fork(0)`, 2 warmup iterations × 3 s + 3 measurement iterations × 5 s per
benchmark (`BenchmarkSuiteTest`'s `OptionsBuilder` overrides iteration *counts* but not the
per-iteration *time*, which comes from `SagaBenchmark`'s own `@Warmup`/`@Measurement`
annotations — confirmed from the raw JMH console header on every run, see `_prep/bench/`).

**Environment:** Apple M3, 8 cores, 8 GB RAM, macOS (Darwin 25.6.0, arm64), Java 21.0.8 (Homebrew),
PostgreSQL 16-alpine (Docker/Testcontainers). Each run below used `mvn test -Pbenchmark`, which
runs **only** `BenchmarkSuiteTest` in its own Surefire fork — no other test classes' Spring
contexts or `@Scheduled` background threads share the JVM, unlike earlier ad-hoc runs.

Run 3 times on 2026-10-29 (raw output: `_prep/bench/run{1,2,3}_20261029.txt`, plus the JMH JSON
result for run 3 at `_prep/bench/run3_20261029.json`). Median and range below, not the best run.

---

## Results

| Benchmark | Mode | Cnt | Run 1 | Run 2 | Run 3 | Median | Range | Unit |
|-----------|------|-----|-------|-------|-------|--------|-------|------|
| `sagaStepLatency` — 1-step saga, orchestrator overhead only | avgt | 3 | 2550.7 | 2276.8 | 2566.9 | **2550.7** | 2276.8–2566.9 | µs/op |
| `compensationLatency` — 2-step saga, step fails → full compensation | avgt | 3 | 5592.6 | 5199.3 | 6420.2 | **5592.6** | 5199.3–6420.2 | µs/op |
| `sagaThroughput` — 5-step fund-transfer saga | thrpt | 3 | ≈10⁻⁴ | ≈10⁻⁴ | 0.0001445 | **≈144** | — | ops/s |
| `entityLockContention` — per-account saga serialization | thrpt | 3 | ≈10⁻⁴ | ≈10⁻⁴ | 0.0001366 | **≈137** | — | ops/s |

The per-op error bars reported by JMH itself are wide relative to the score — e.g. run 3's
`compensationLatency` is 6420 ± 11376 µs/op, an error *larger than the measurement*. With only 3
measurement iterations and `@Fork(0)`, these numbers should be read as "the right order of
magnitude, measured against a real Postgres instance" — not as a precise, reproducible latency
figure. Treat the range column above as the honest uncertainty, not the median alone.

For `sagaThroughput`/`entityLockContention`, JMH's own console output rounds the score to
`≈10⁻⁴ ops/µs` on every run (not a rounding choice made in this document — that is literally
what the tool printed each time); the one run where the full-precision JSON result was kept
(run 3) gives 0.0001445 and 0.0001366 ops/µs respectively, i.e. ≈144 and ≈137 ops/s. All three
runs are consistent at the "~100–150 ops/s" order of magnitude.

---

## Interpretation

### sagaStepLatency — ~2.3–2.6ms/op
Time for a complete saga with one step: entity lock acquire → write intent → execute action →
persist result → release lock. This measures pure orchestrator overhead. The bulk of this is 3
serialized DB round-trips over a loopback TCP connection to the Testcontainers container.

### compensationLatency — ~5.2–6.4ms/op
Full compensation cycle from step failure to COMPENSATED terminal state:
- Step 0 executes (1 intent + 1 result write)
- Step 1 fails (1 intent + 1 failure write)
- Compensation plan written atomically with COMPENSATING status
- Step 0 compensation executes (1 intent + 1 result write)
- Lock release + COMPENSATED status written
Total: 8 DB writes across 4 REQUIRES_NEW transactions.

### sagaThroughput / entityLockContention — ~100–150 ops/s
Complete 5-step sagas per second, single-threaded against containerized PostgreSQL. Each saga
issues ~25 DB writes (5 intents + 5 results + 1 init + 1 terminal + lock ops). At this throughput
and ~25 writes/op, the engine drives roughly 2,500–3,750 DB writes/s — consistent with
PostgreSQL's synchronous commit overhead on loopback.

**Production context:** With a shared PostgreSQL connection pool, async saga dispatch, and
multiple orchestrator threads, throughput scales horizontally. The Quartz JDBC store enables
clustered deployment across nodes. These numbers say nothing about that scaled-out case — they
measure one JVM, one DB connection pool, one thread, sequential saga starts.

---

## Run Instructions

```bash
# Run benchmarks only — separate Maven profile, isolated from the rest of the test suite
mvn test -Pbenchmark

# Results also written to benchmark-results.json (JMH JSON format) in the project root —
# this is overwritten on every run; copy it out first if you want to keep more than one.
```
