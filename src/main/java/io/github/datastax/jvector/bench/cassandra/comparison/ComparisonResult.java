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

import java.util.HashMap;
import java.util.Map;

/**
 * Holds comparison results between jvector-direct and Cassandra benchmarks.
 */
public class ComparisonResult {
    private final String dataset;
    private final Map<String, MetricComparison> metrics;
    private final Map<String, String> metadata;

    public ComparisonResult(String dataset) {
        this.dataset = dataset;
        this.metrics = new HashMap<>();
        this.metadata = new HashMap<>();
    }

    public void addMetric(String name, double jvectorValue, double cassandraValue) {
        metrics.put(name, new MetricComparison(name, jvectorValue, cassandraValue));
    }

    public void addMetadata(String key, String value) {
        metadata.put(key, value);
    }

    public String getDataset() {
        return dataset;
    }

    public Map<String, MetricComparison> getMetrics() {
        return metrics;
    }

    public Map<String, String> getMetadata() {
        return metadata;
    }

    public MetricComparison getMetric(String name) {
        return metrics.get(name);
    }

    /**
     * Represents a single metric comparison with overhead calculation.
     */
    public static class MetricComparison {
        private final String name;
        private final double jvectorValue;
        private final double cassandraValue;
        private final double absoluteDifference;
        private final double percentageDifference;
        private final OverheadType overheadType;

        public MetricComparison(String name, double jvectorValue, double cassandraValue) {
            this.name = name;
            this.jvectorValue = jvectorValue;
            this.cassandraValue = cassandraValue;
            this.absoluteDifference = cassandraValue - jvectorValue;

            // Calculate percentage difference
            // For latency/time metrics: positive = slower (overhead)
            // For throughput/QPS metrics: negative = slower (overhead)
            if (jvectorValue != 0) {
                this.percentageDifference = ((cassandraValue - jvectorValue) / jvectorValue) * 100;
            } else {
                this.percentageDifference = 0;
            }

            // Determine overhead type
            this.overheadType = determineOverheadType(name);
        }

        private OverheadType determineOverheadType(String metricName) {
            String lower = metricName.toLowerCase();
            if (lower.contains("qps") || lower.contains("throughput")) {
                return OverheadType.THROUGHPUT;
            } else if (lower.contains("latency") || lower.contains("time")) {
                return OverheadType.LATENCY;
            } else if (lower.contains("recall") || lower.contains("map") || lower.contains("accuracy")) {
                return OverheadType.ACCURACY;
            } else {
                return OverheadType.OTHER;
            }
        }

        public String getName() {
            return name;
        }

        public double getJvectorValue() {
            return jvectorValue;
        }

        public double getCassandraValue() {
            return cassandraValue;
        }

        public double getAbsoluteDifference() {
            return absoluteDifference;
        }

        public double getPercentageDifference() {
            return percentageDifference;
        }

        public OverheadType getOverheadType() {
            return overheadType;
        }

        /**
         * Get overhead interpretation (considers metric type).
         * For throughput, negative percentage = overhead.
         * For latency, positive percentage = overhead.
         */
        public double getOverheadPercentage() {
            if (overheadType == OverheadType.THROUGHPUT) {
                // For throughput, lower is worse, so invert the sign
                return -percentageDifference;
            } else {
                // For latency and other metrics, higher is worse
                return percentageDifference;
            }
        }

        public boolean isSignificant(double threshold) {
            return Math.abs(getOverheadPercentage()) >= threshold;
        }

        public String getInterpretation() {
            double overhead = getOverheadPercentage();

            if (overheadType == OverheadType.ACCURACY) {
                if (Math.abs(percentageDifference) < 0.1) {
                    return "✓ Identical (as expected)";
                } else if (Math.abs(percentageDifference) < 1.0) {
                    return "⚠ Minor difference (acceptable)";
                } else {
                    return "✗ Significant difference (investigate!)";
                }
            }

            if (Math.abs(overhead) < 5) {
                return "✓ Negligible overhead";
            } else if (Math.abs(overhead) < 15) {
                return "○ Minor overhead";
            } else if (Math.abs(overhead) < 30) {
                return "⚠ Moderate overhead";
            } else if (Math.abs(overhead) < 50) {
                return "⚠ Significant overhead";
            } else {
                return "✗ Severe overhead (investigate!)";
            }
        }
    }

    /**
     * Type of metric being compared.
     */
    public enum OverheadType {
        THROUGHPUT,  // QPS, queries/sec
        LATENCY,     // ms, time
        ACCURACY,    // Recall, MAP
        OTHER
    }
}
