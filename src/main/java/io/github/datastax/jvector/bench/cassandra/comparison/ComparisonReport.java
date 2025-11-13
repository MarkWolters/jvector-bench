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

package io.github.datastax.jvector.bench.cassandra.comparison;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.datastax.jvector.bench.BenchResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * Generates comparison reports between jvector-direct and Cassandra benchmark results.
 * Analyzes performance differences and identifies overhead sources.
 */
public class ComparisonReport {
    private static final Logger logger = LoggerFactory.getLogger(ComparisonReport.class);

    private final List<BenchResult> jvectorResults;
    private final List<BenchResult> cassandraResults;
    private final double significanceThreshold;

    public ComparisonReport(List<BenchResult> jvectorResults,
                           List<BenchResult> cassandraResults,
                           double significanceThreshold) {
        this.jvectorResults = jvectorResults;
        this.cassandraResults = cassandraResults;
        this.significanceThreshold = significanceThreshold;
    }

    /**
     * Load benchmark results from JSON files.
     */
    public static ComparisonReport fromFiles(String jvectorPath, String cassandraPath, double threshold)
            throws IOException {
        ObjectMapper mapper = new ObjectMapper();

        List<BenchResult> jvectorResults = Arrays.asList(
            mapper.readValue(new File(jvectorPath), BenchResult[].class)
        );

        List<BenchResult> cassandraResults = Arrays.asList(
            mapper.readValue(new File(cassandraPath), BenchResult[].class)
        );

        return new ComparisonReport(jvectorResults, cassandraResults, threshold);
    }

    /**
     * Generate comparison results by dataset.
     */
    public Map<String, ComparisonResult> compare() {
        Map<String, ComparisonResult> results = new HashMap<>();

        // Group results by dataset
        Map<String, List<BenchResult>> jvectorByDataset = groupByDataset(jvectorResults);
        Map<String, List<BenchResult>> cassandraByDataset = groupByDataset(cassandraResults);

        // Compare each dataset
        for (String dataset : jvectorByDataset.keySet()) {
            if (!cassandraByDataset.containsKey(dataset)) {
                logger.warn("Dataset {} found in jvector results but not in Cassandra results", dataset);
                continue;
            }

            ComparisonResult comparison = compareDataset(
                dataset,
                jvectorByDataset.get(dataset),
                cassandraByDataset.get(dataset)
            );

            results.put(dataset, comparison);
        }

        return results;
    }

    /**
     * Compare results for a single dataset.
     */
    private ComparisonResult compareDataset(String dataset,
                                           List<BenchResult> jvectorResults,
                                           List<BenchResult> cassandraResults) {
        ComparisonResult result = new ComparisonResult(dataset);

        // Extract metrics from both sources
        Map<String, Double> jvectorMetrics = extractMetrics(jvectorResults);
        Map<String, Double> cassandraMetrics = extractMetrics(cassandraResults);

        // Compare common metrics
        Set<String> allMetrics = new HashSet<>();
        allMetrics.addAll(jvectorMetrics.keySet());
        allMetrics.addAll(cassandraMetrics.keySet());

        for (String metric : allMetrics) {
            Double jvectorValue = jvectorMetrics.get(metric);
            Double cassandraValue = cassandraMetrics.get(metric);

            if (jvectorValue != null && cassandraValue != null) {
                result.addMetric(metric, jvectorValue, cassandraValue);
            } else if (jvectorValue == null) {
                logger.debug("Metric {} not found in jvector results for dataset {}", metric, dataset);
            } else {
                logger.debug("Metric {} not found in Cassandra results for dataset {}", metric, dataset);
            }
        }

        // Add metadata
        result.addMetadata("jvectorResultCount", String.valueOf(jvectorResults.size()));
        result.addMetadata("cassandraResultCount", String.valueOf(cassandraResults.size()));

        return result;
    }

    /**
     * Extract metrics from benchmark results.
     * Takes the most recent or average value for each metric.
     */
    private Map<String, Double> extractMetrics(List<BenchResult> results) {
        Map<String, List<Double>> metricValues = new HashMap<>();

        for (BenchResult result : results) {
            for (Map.Entry<String, Object> entry : result.metrics.entrySet()) {
                String metricName = entry.getKey();
                Object value = entry.getValue();

                if (value instanceof Number) {
                    metricValues.computeIfAbsent(metricName, k -> new ArrayList<>())
                               .add(((Number) value).doubleValue());
                }
            }
        }

        // Calculate average for each metric
        Map<String, Double> averages = new HashMap<>();
        for (Map.Entry<String, List<Double>> entry : metricValues.entrySet()) {
            double avg = entry.getValue().stream()
                             .mapToDouble(Double::doubleValue)
                             .average()
                             .orElse(0.0);
            averages.put(entry.getKey(), avg);
        }

        return averages;
    }

    /**
     * Group results by dataset name.
     */
    private Map<String, List<BenchResult>> groupByDataset(List<BenchResult> results) {
        Map<String, List<BenchResult>> grouped = new HashMap<>();

        for (BenchResult result : results) {
            grouped.computeIfAbsent(result.dataset, k -> new ArrayList<>()).add(result);
        }

        return grouped;
    }

    /**
     * Get summary of all comparisons.
     */
    public String getSummary() {
        Map<String, ComparisonResult> comparisons = compare();

        StringBuilder sb = new StringBuilder();
        sb.append("=== Benchmark Comparison Summary ===\n\n");

        for (Map.Entry<String, ComparisonResult> entry : comparisons.entrySet()) {
            String dataset = entry.getKey();
            ComparisonResult comparison = entry.getValue();

            sb.append(String.format("Dataset: %s%n", dataset));
            sb.append("─".repeat(50)).append("\n");

            for (ComparisonResult.MetricComparison metric : comparison.getMetrics().values()) {
                if (metric.isSignificant(significanceThreshold)) {
                    sb.append(String.format("  %-25s  JVector: %10.2f  Cassandra: %10.2f  Overhead: %+6.1f%%  %s%n",
                        metric.getName(),
                        metric.getJvectorValue(),
                        metric.getCassandraValue(),
                        metric.getOverheadPercentage(),
                        metric.getInterpretation()
                    ));
                }
            }
            sb.append("\n");
        }

        return sb.toString();
    }

    /**
     * Get list of significant differences.
     */
    public List<String> getSignificantDifferences() {
        List<String> differences = new ArrayList<>();
        Map<String, ComparisonResult> comparisons = compare();

        for (Map.Entry<String, ComparisonResult> entry : comparisons.entrySet()) {
            String dataset = entry.getKey();
            ComparisonResult comparison = entry.getValue();

            for (ComparisonResult.MetricComparison metric : comparison.getMetrics().values()) {
                if (metric.isSignificant(significanceThreshold)) {
                    differences.add(String.format("%s - %s: %+.1f%% overhead",
                        dataset, metric.getName(), metric.getOverheadPercentage()));
                }
            }
        }

        return differences;
    }
}
