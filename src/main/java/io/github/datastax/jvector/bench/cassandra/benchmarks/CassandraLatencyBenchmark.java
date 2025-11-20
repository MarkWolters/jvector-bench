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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Measures per-query latency through Cassandra.
 * Calculates mean, standard deviation, and various percentiles (p50, p95, p99, p999).
 */
public class CassandraLatencyBenchmark implements CassandraBenchmark {
    private static final Logger logger = LoggerFactory.getLogger(CassandraLatencyBenchmark.class);
    private static final String DEFAULT_FORMAT = ".3f";

    private static volatile long SINK;

    public CassandraLatencyBenchmark() {
    }

    public static CassandraLatencyBenchmark createDefault() {
        return new CassandraLatencyBenchmark();
    }

    @Override
    public String getBenchmarkName() {
        return "CassandraLatencyBenchmark";
    }

    @Override
    public List<Metric> runBenchmark(
            CassandraConnection connection,
            DataSet ds,
            int topK,
            SearchConfig searchConfig,
            int queryRuns) {

        int totalQueries = ds.queryVectors.size();
        logger.info("Running latency benchmark with {} queries across {} runs", totalQueries, queryRuns);

        // Prepare search statement once
        connection.prepareSearch(searchConfig);

        List<Long> latencies = new ArrayList<>(totalQueries * queryRuns);

        // Welford's online algorithm for mean and variance
        double mean = 0.0;
        double m2 = 0.0;
        int count = 0;

        // Run queries and collect latencies
        for (int run = 0; run < queryRuns; run++) {
            logger.debug("Latency measurement run {}/{}", run + 1, queryRuns);

            for (int i = 0; i < totalQueries; i++) {
                VectorFloat<?> query = ds.queryVectors.get(i);

                long start = System.nanoTime();
                SearchResult sr = connection.search(query, topK, searchConfig);
                long duration = System.nanoTime() - start;

                latencies.add(duration);
                SINK += sr.getNodes().length;

                // Update running statistics
                count++;
                double delta = duration - mean;
                mean += delta / count;
                m2 += delta * (duration - mean);
            }
        }

        // Convert to milliseconds
        mean /= 1e6;
        double stdDev = (count > 0) ? Math.sqrt(m2 / count) / 1e6 : 0.0;

        // Calculate percentiles
        Collections.sort(latencies);
        double p50 = getPercentile(latencies, 0.50) / 1e6;
        double p95 = getPercentile(latencies, 0.95) / 1e6;
        double p99 = getPercentile(latencies, 0.99) / 1e6;
        double p999 = getPercentile(latencies, 0.999) / 1e6;

        logger.info("Latency benchmark complete: mean={}ms, p50={}ms, p99={}ms, p999={}ms",
            mean, p50, p99, p999);

        List<Metric> metrics = new ArrayList<>();
        metrics.add(Metric.of("Mean Latency (ms)", DEFAULT_FORMAT, mean));
        metrics.add(Metric.of("STD Latency (ms)", DEFAULT_FORMAT, stdDev));
        metrics.add(Metric.of("p50 Latency (ms)", DEFAULT_FORMAT, p50));
        metrics.add(Metric.of("p95 Latency (ms)", DEFAULT_FORMAT, p95));
        metrics.add(Metric.of("p99 Latency (ms)", DEFAULT_FORMAT, p99));
        metrics.add(Metric.of("p999 Latency (ms)", DEFAULT_FORMAT, p999));

        return metrics;
    }

    /**
     * Calculate percentile from sorted list.
     */
    private double getPercentile(List<Long> sortedValues, double percentile) {
        if (sortedValues.isEmpty()) {
            return 0.0;
        }

        int index = (int) Math.ceil(percentile * sortedValues.size()) - 1;
        index = Math.max(0, Math.min(index, sortedValues.size() - 1));

        return sortedValues.get(index);
    }
}
