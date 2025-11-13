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

import java.util.ArrayList;
import java.util.List;

/**
 * Analyzes overhead sources in Cassandra vs jvector-direct benchmarks.
 * Provides estimates for where the performance difference comes from.
 */
public class OverheadAnalyzer {

    /**
     * Analyze overhead sources for a comparison result.
     */
    public static OverheadAnalysis analyze(ComparisonResult comparison) {
        OverheadAnalysis analysis = new OverheadAnalysis(comparison.getDataset());

        // Get key metrics
        ComparisonResult.MetricComparison qps = comparison.getMetric("Avg QPS (of 3)");
        ComparisonResult.MetricComparison meanLatency = comparison.getMetric("Mean Latency (ms)");
        ComparisonResult.MetricComparison p99Latency = comparison.getMetric("p99 Latency (ms)");
        ComparisonResult.MetricComparison recall = comparison.getMetric("Recall@10");

        // Analyze throughput overhead
        if (qps != null) {
            double qpsOverhead = qps.getOverheadPercentage();
            analysis.addSource("Network + CQL Protocol", estimateNetworkOverhead(qpsOverhead));
            analysis.addSource("Coordinator Processing", estimateCoordinatorOverhead(qpsOverhead));
            analysis.addSource("Storage Layer Access", estimateStorageOverhead(qpsOverhead));
            analysis.addSource("Result Serialization", estimateSerializationOverhead(qpsOverhead));
        }

        // Analyze latency overhead
        if (meanLatency != null) {
            double latencyOverhead = meanLatency.getOverheadPercentage();
            analysis.addRecommendation(generateLatencyRecommendation(latencyOverhead, meanLatency));
        }

        // Analyze tail latency
        if (p99Latency != null) {
            double p99Overhead = p99Latency.getOverheadPercentage();
            if (p99Overhead > 100) {
                analysis.addRecommendation("⚠ High p99 latency overhead (" + String.format("%.1f", p99Overhead) +
                    "%) suggests GC pauses or network variance. Monitor JVM metrics and network stability.");
            }
        }

        // Check accuracy
        if (recall != null) {
            double recallDiff = Math.abs(recall.getPercentageDifference());
            if (recallDiff > 1.0) {
                analysis.addRecommendation("✗ CRITICAL: Recall differs by " + String.format("%.2f", recallDiff) +
                    "%. Check index configuration matches exactly!");
            } else if (recallDiff > 0.1) {
                analysis.addRecommendation("⚠ Minor recall difference (" + String.format("%.2f", recallDiff) +
                    "%). Acceptable but worth monitoring.");
            } else {
                analysis.addRecommendation("✓ Recall matches jvector-direct (as expected)");
            }
        }

        return analysis;
    }

    /**
     * Estimate network + CQL overhead percentage.
     */
    private static double estimateNetworkOverhead(double totalOverhead) {
        // Network typically contributes 5-15% of total overhead
        // For local connections: ~5-8%
        // For remote connections: ~10-15%
        return Math.min(totalOverhead * 0.3, 15.0);
    }

    /**
     * Estimate Cassandra coordinator overhead.
     */
    private static double estimateCoordinatorOverhead(double totalOverhead) {
        // Coordinator processing: ~3-8% typically
        return Math.min(totalOverhead * 0.2, 8.0);
    }

    /**
     * Estimate storage layer access overhead.
     */
    private static double estimateStorageOverhead(double totalOverhead) {
        // Storage access (memtable/SSTable): ~5-10%
        return Math.min(totalOverhead * 0.25, 10.0);
    }

    /**
     * Estimate result serialization overhead.
     */
    private static double estimateSerializationOverhead(double totalOverhead) {
        // Serialization/deserialization: ~2-5%
        return Math.min(totalOverhead * 0.15, 5.0);
    }

    /**
     * Generate recommendation based on latency overhead.
     */
    private static String generateLatencyRecommendation(double overhead,
                                                        ComparisonResult.MetricComparison latency) {
        if (overhead < 15) {
            return String.format("✓ Latency overhead (%.1f%%) is acceptable for full-stack testing",
                overhead);
        } else if (overhead < 30) {
            return String.format("○ Moderate latency overhead (%.1f%%). Consider connection pooling tuning.",
                overhead);
        } else if (overhead < 50) {
            return String.format("⚠ Significant latency overhead (%.1f%%). " +
                "Check network latency, connection pool settings, and Cassandra load.",
                overhead);
        } else {
            return String.format("✗ Severe latency overhead (%.1f%%). " +
                "Investigate: network issues, Cassandra overload, or misconfiguration.",
                overhead);
        }
    }

    /**
     * Results of overhead analysis.
     */
    public static class OverheadAnalysis {
        private final String dataset;
        private final List<OverheadSource> sources;
        private final List<String> recommendations;

        public OverheadAnalysis(String dataset) {
            this.dataset = dataset;
            this.sources = new ArrayList<>();
            this.recommendations = new ArrayList<>();
        }

        public void addSource(String name, double percentage) {
            sources.add(new OverheadSource(name, percentage));
        }

        public void addRecommendation(String recommendation) {
            recommendations.add(recommendation);
        }

        public String getDataset() {
            return dataset;
        }

        public List<OverheadSource> getSources() {
            return sources;
        }

        public List<String> getRecommendations() {
            return recommendations;
        }

        public String getSummary() {
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("=== Overhead Analysis: %s ===%n%n", dataset));

            if (!sources.isEmpty()) {
                sb.append("Estimated Overhead Sources:%n");
                double total = 0;
                for (OverheadSource source : sources) {
                    sb.append(String.format("  %-30s  ~%.1f%%%n", source.getName(), source.getPercentage()));
                    total += source.getPercentage();
                }
                sb.append(String.format("  %-30s  ~%.1f%%%n%n", "Total Estimated:", total));
            }

            if (!recommendations.isEmpty()) {
                sb.append("Recommendations:%n");
                for (String rec : recommendations) {
                    sb.append(String.format("  • %s%n", rec));
                }
            }

            return sb.toString();
        }
    }

    /**
     * Represents a single overhead source.
     */
    public static class OverheadSource {
        private final String name;
        private final double percentage;

        public OverheadSource(String name, double percentage) {
            this.name = name;
            this.percentage = percentage;
        }

        public String getName() {
            return name;
        }

        public double getPercentage() {
            return percentage;
        }
    }
}
