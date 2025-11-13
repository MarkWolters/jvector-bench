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

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * Formats comparison reports as Markdown.
 */
public class MarkdownReportFormatter implements ReportFormatter {

    @Override
    public String format(Map<String, ComparisonResult> comparisons,
                        Map<String, OverheadAnalyzer.OverheadAnalysis> analyses) {
        StringBuilder sb = new StringBuilder();

        // Header
        sb.append("# JVector vs Cassandra Benchmark Comparison\n\n");
        sb.append("**Generated:** ").append(LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)).append("\n\n");
        sb.append("---\n\n");

        // For each dataset
        for (Map.Entry<String, ComparisonResult> entry : comparisons.entrySet()) {
            String dataset = entry.getKey();
            ComparisonResult comparison = entry.getValue();

            sb.append("## Dataset: ").append(dataset).append("\n\n");

            // Metrics table
            sb.append("### Performance Metrics\n\n");
            sb.append("| Metric | JVector Direct | Cassandra | Difference | Overhead | Status |\n");
            sb.append("|--------|---------------|-----------|------------|----------|--------|\n");

            for (ComparisonResult.MetricComparison metric : comparison.getMetrics().values()) {
                sb.append(String.format("| %s | %.2f | %.2f | %+.2f | %+.1f%% | %s |\n",
                    metric.getName(),
                    metric.getJvectorValue(),
                    metric.getCassandraValue(),
                    metric.getAbsoluteDifference(),
                    metric.getOverheadPercentage(),
                    metric.getInterpretation()
                ));
            }
            sb.append("\n");

            // Overhead analysis
            if (analyses.containsKey(dataset)) {
                OverheadAnalyzer.OverheadAnalysis analysis = analyses.get(dataset);

                sb.append("### Overhead Analysis\n\n");

                if (!analysis.getSources().isEmpty()) {
                    sb.append("**Estimated Overhead Sources:**\n\n");
                    sb.append("| Source | Estimated % |\n");
                    sb.append("|--------|-------------|\n");

                    double total = 0;
                    for (OverheadAnalyzer.OverheadSource source : analysis.getSources()) {
                        sb.append(String.format("| %s | ~%.1f%% |\n",
                            source.getName(), source.getPercentage()));
                        total += source.getPercentage();
                    }
                    sb.append(String.format("| **Total** | **~%.1f%%** |\n\n", total));
                }

                if (!analysis.getRecommendations().isEmpty()) {
                    sb.append("**Recommendations:**\n\n");
                    for (String rec : analysis.getRecommendations()) {
                        sb.append("- ").append(rec).append("\n");
                    }
                    sb.append("\n");
                }
            }

            sb.append("---\n\n");
        }

        // Summary
        sb.append("## Summary\n\n");
        sb.append("This report compares jvector-direct performance with Cassandra integration performance. ");
        sb.append("The overhead represents the additional cost of going through Cassandra's full stack ");
        sb.append("(network, CQL, storage layer, etc.) versus calling jvector directly.\n\n");

        sb.append("**Key Points:**\n\n");
        sb.append("- **Throughput (QPS):** Lower is worse, negative overhead indicates slower performance\n");
        sb.append("- **Latency:** Higher is worse, positive overhead indicates slower performance\n");
        sb.append("- **Accuracy (Recall):** Should be identical - any difference indicates configuration mismatch\n");
        sb.append("- **Expected Overhead:** 20-40% slower QPS, 15-50% higher latency for local single-node clusters\n\n");

        return sb.toString();
    }

    @Override
    public String getFileExtension() {
        return "md";
    }
}
