/*
 * Copyright DataStax, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.github.datastax.jvector.bench.cassandra.benchmarks;

import io.github.datastax.jvector.bench.benchmarks.Metric;
import io.github.datastax.jvector.bench.cassandra.CassandraConnection;
import io.github.datastax.jvector.bench.cassandra.config.SearchConfig;
import io.github.datastax.jvector.bench.util.DataSet;
import io.github.jbellis.jvector.graph.SearchResult;
import io.github.jbellis.jvector.vector.types.VectorFloat;
import org.apache.commons.math3.stat.StatUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.LongAdder;
import java.util.stream.IntStream;

/**
 * Measures query throughput (QPS) through Cassandra.
 * Includes warmup phase and multiple test runs for statistical stability.
 */
public class CassandraThroughputBenchmark implements CassandraBenchmark {
    private static final Logger logger = LoggerFactory.getLogger(CassandraThroughputBenchmark.class);
    private static final String DEFAULT_FORMAT = ".1f";

    private static volatile long SINK;

    private final int numWarmupRuns;
    private final int numTestRuns;

    public CassandraThroughputBenchmark() {
        this(3, 3);
    }

    public CassandraThroughputBenchmark(int numWarmupRuns, int numTestRuns) {
        this.numWarmupRuns = numWarmupRuns;
        this.numTestRuns = numTestRuns;
    }

    public static CassandraThroughputBenchmark createDefault() {
        return new CassandraThroughputBenchmark(3, 3);
    }

    @Override
    public String getBenchmarkName() {
        return "CassandraThroughputBenchmark";
    }

    @Override
    public List<Metric> runBenchmark(
            CassandraConnection connection,
            DataSet ds,
            int topK,
            SearchConfig searchConfig,
            int queryRuns) {

        int totalQueries = ds.queryVectors.size();
        logger.info("Running throughput benchmark with {} queries", totalQueries);

        // Prepare search statement once
        connection.prepareSearch(searchConfig);

        // Track failures across all phases
        final java.util.concurrent.atomic.AtomicInteger totalFailures = new java.util.concurrent.atomic.AtomicInteger(0);

        // Warmup phase
        logger.info("Warming up with {} runs...", numWarmupRuns);
        for (int warmup = 0; warmup < numWarmupRuns; warmup++) {
            IntStream.range(0, totalQueries)
                .parallel()
                .forEach(i -> {
                    VectorFloat<?> query = ds.queryVectors.get(i);
                    SearchResult sr = connection.search(query, topK, searchConfig);
                    if (sr != null) {
                        SINK += sr.getNodes().length;
                    } else {
                        totalFailures.incrementAndGet();
                    }
                });
            logger.debug("Warmup run {} complete", warmup);
        }

        // Test phase
        logger.info("Running {} test runs...", numTestRuns);
        double[] qpsSamples = new double[numTestRuns];
        int[] successCounts = new int[numTestRuns];

        for (int run = 0; run < numTestRuns; run++) {
            // GC between runs
            System.gc();
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            long start = System.nanoTime();
            LongAdder counter = new LongAdder();
            java.util.concurrent.atomic.AtomicInteger successCount = new java.util.concurrent.atomic.AtomicInteger(0);
            java.util.concurrent.atomic.AtomicInteger failCount = new java.util.concurrent.atomic.AtomicInteger(0);

            IntStream.range(0, totalQueries)
                .parallel()
                .forEach(i -> {
                    VectorFloat<?> query = ds.queryVectors.get(i);
                    SearchResult sr = connection.search(query, topK, searchConfig);
                    if (sr != null) {
                        counter.add(sr.getNodes().length);
                        successCount.incrementAndGet();
                    } else {
                        failCount.incrementAndGet();
                        totalFailures.incrementAndGet();
                    }
                });

            double elapsed = (System.nanoTime() - start) / 1e9;
            int successful = successCount.get();
            successCounts[run] = successful;
            qpsSamples[run] = successful / elapsed;  // QPS based on successful queries only
            SINK += counter.sum();

            logger.info("Test run {}: {:.1f} QPS ({} successful, {} failed)",
                run, qpsSamples[run], successful, failCount.get());
        }

        // Calculate statistics
        double avgQps = StatUtils.mean(qpsSamples);
        double stdDev = Math.sqrt(StatUtils.variance(qpsSamples));
        double cv = (stdDev / avgQps) * 100;

        // Calculate average success rate
        double avgSuccessCount = StatUtils.mean(java.util.Arrays.stream(successCounts).asDoubleStream().toArray());
        double successRate = (avgSuccessCount / totalQueries) * 100.0;

        logger.info("Throughput benchmark complete: {:.1f} ± {:.1f} QPS (CV: {:.1f}%)",
            avgQps, stdDev, cv);
        
        if (totalFailures.get() > 0) {
            logger.warn("Total failed queries across all runs: {}", totalFailures.get());
        }

        List<Metric> metrics = new ArrayList<>();
        metrics.add(Metric.of("Avg QPS (of " + numTestRuns + ")", DEFAULT_FORMAT, avgQps));
        metrics.add(Metric.of("± Std Dev", DEFAULT_FORMAT, stdDev));
        metrics.add(Metric.of("CV %", DEFAULT_FORMAT, cv));
        
        // Add failure metrics if there were any failures
        if (totalFailures.get() > 0) {
            int totalAttempts = totalQueries * (numWarmupRuns + numTestRuns);
            double failureRate = (totalFailures.get() * 100.0) / totalAttempts;
            metrics.add(Metric.of("Success Rate (%)", ".2f", successRate));
            metrics.add(Metric.of("Total Failed Queries", ".0f", (double) totalFailures.get()));
            metrics.add(Metric.of("Overall Failure Rate (%)", ".2f", failureRate));
        }

        return metrics;
    }
}
