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
import io.github.datastax.jvector.bench.util.AccuracyMetrics;
import io.github.datastax.jvector.bench.util.DataSet;
import io.github.jbellis.jvector.graph.SearchResult;
import io.github.jbellis.jvector.vector.types.VectorFloat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Measures search accuracy through Cassandra by comparing results against ground truth.
 * Calculates Recall@K and MAP@K.
 *
 * CRITICAL: Accuracy should match jvector-direct benchmarks exactly.
 * Any discrepancy indicates a configuration mismatch or integration issue.
 */
public class CassandraAccuracyBenchmark implements CassandraBenchmark {
    private static final Logger logger = LoggerFactory.getLogger(CassandraAccuracyBenchmark.class);
    private static final String DEFAULT_FORMAT = ".4f";

    public CassandraAccuracyBenchmark() {
    }

    public static CassandraAccuracyBenchmark createDefault() {
        return new CassandraAccuracyBenchmark();
    }

    @Override
    public String getBenchmarkName() {
        return "CassandraAccuracyBenchmark";
    }

    @Override
    public List<Metric> runBenchmark(
            CassandraConnection connection,
            DataSet ds,
            int topK,
            SearchConfig searchConfig,
            int queryRuns) {

        int totalQueries = ds.queryVectors.size();
        logger.info("Running accuracy benchmark with {} queries", totalQueries);

        // Prepare search statement once
        connection.prepareSearch(searchConfig);

        // Track failures
        final java.util.concurrent.atomic.AtomicInteger failedQueries = new java.util.concurrent.atomic.AtomicInteger(0);

        // Execute all queries in parallel and collect results
        List<SearchResult> results = IntStream.range(0, totalQueries)
            .parallel()
            .mapToObj(i -> {
                VectorFloat<?> query = ds.queryVectors.get(i);
                SearchResult sr = connection.search(query, topK, searchConfig);
                if (sr == null) {
                    failedQueries.incrementAndGet();
                    logger.debug("Query {} failed, will be excluded from accuracy calculation", i);
                    // Return empty result for failed queries to maintain index alignment
                    return new SearchResult(new SearchResult.NodeScore[0], -1, -1, -1, -1, -1.0f);
                }
                return sr;
            })
            .collect(Collectors.toList());

        logger.debug("All queries executed, calculating accuracy metrics...");

        // Log failure statistics
        int failed = failedQueries.get();
        if (failed > 0) {
            double failureRate = (failed * 100.0) / totalQueries;
            logger.warn("Accuracy benchmark had {} failed queries out of {} ({}%)",
                failed, totalQueries, failureRate);
        }

        // Calculate recall (AccuracyMetrics will handle empty results gracefully)
        double recall = AccuracyMetrics.recallFromSearchResults(
            ds.groundTruth, results, topK, topK
        );

        // Calculate MAP
        double map = AccuracyMetrics.meanAveragePrecisionAtK(
            ds.groundTruth, results, topK
        );

        logger.info("Accuracy benchmark complete: Recall@{}={}, MAP@{}={}",
            topK, recall, topK, map);

        // Log warning if recall seems suspiciously low
        if (recall < 0.5 && failed == 0) {
            logger.warn("WARNING: Recall@{} is unusually low ({}). " +
                       "This may indicate a configuration mismatch or data loading issue.", topK, recall);
        } else if (recall < 0.5 && failed > 0) {
            logger.warn("WARNING: Recall@{} is unusually low ({}). " +
                       "This is likely due to {} failed queries.", topK, recall, failed);
        }

        List<Metric> metrics = new ArrayList<>();
        metrics.add(Metric.of("Recall@" + topK, DEFAULT_FORMAT, recall));
        metrics.add(Metric.of("MAP@" + topK, DEFAULT_FORMAT, map));
        
        // Add failure metrics if there were any failures
        if (failed > 0) {
            double failureRate = (failed * 100.0) / totalQueries;
            double successRate = ((totalQueries - failed) * 100.0) / totalQueries;
            metrics.add(Metric.of("Failed Queries", ".0f", (double) failed));
            metrics.add(Metric.of("Success Rate (%)", ".2f", successRate));
            metrics.add(Metric.of("Failure Rate (%)", ".2f", failureRate));
        }

        return metrics;
    }
}
