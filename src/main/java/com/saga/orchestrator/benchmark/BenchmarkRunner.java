package com.saga.orchestrator.benchmark;

import org.openjdk.jmh.results.format.ResultFormatType;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

/**
 * Entry point for running JMH benchmarks manually.
 *
 * Usage:
 *   mvn package -DskipTests
 *   java -cp "target/classes:$(find ~/.m2 -name '*.jar' | tr '\n' ':')" \
 *        com.saga.orchestrator.benchmark.BenchmarkRunner
 *
 * Or via Maven Surefire (skipped by default — run explicitly):
 *   mvn test -Dtest=BenchmarkRunner -DfailIfNoSpecifiedTests=false
 *
 * Results are written to benchmark-results.json for post-processing.
 */
public class BenchmarkRunner {

    public static void main(String[] args) throws Exception {
        Options opts = new OptionsBuilder()
                .include(SagaBenchmark.class.getSimpleName())
                .forks(0)
                .warmupIterations(3)
                .measurementIterations(5)
                .resultFormat(ResultFormatType.JSON)
                .result("benchmark-results.json")
                .build();

        new Runner(opts).run();
    }
}
