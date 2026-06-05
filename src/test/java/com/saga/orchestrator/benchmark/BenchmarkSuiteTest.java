package com.saga.orchestrator.benchmark;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.openjdk.jmh.results.RunResult;
import org.openjdk.jmh.results.format.ResultFormatType;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.util.Collection;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs JMH benchmarks via Maven Surefire (full classpath + Testcontainers available).
 *
 * Excluded from the default test run — tag "benchmark" must be included explicitly:
 *   mvn test -Dgroups=benchmark
 *
 * Results written to benchmark-results.json in the project root.
 */
@Tag("benchmark")
class BenchmarkSuiteTest {

    @Test
    void runBenchmarks() throws Exception {
        Options opts = new OptionsBuilder()
                .include(SagaBenchmark.class.getSimpleName())
                .forks(0)
                .warmupIterations(2)
                .measurementIterations(3)
                .resultFormat(ResultFormatType.JSON)
                .result("benchmark-results.json")
                .build();

        Collection<RunResult> results = new Runner(opts).run();

        assertThat(results)
                .as("all benchmarks must complete without error")
                .isNotEmpty();

        results.forEach(r -> {
            System.out.printf("%-45s  %8.2f ± %6.2f  %s%n",
                    r.getPrimaryResult().getLabel(),
                    r.getPrimaryResult().getScore(),
                    r.getPrimaryResult().getScoreError(),
                    r.getPrimaryResult().getScoreUnit());
        });
    }
}
