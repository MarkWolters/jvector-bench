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
 * Formats comparison reports as HTML with basic styling.
 */
public class HtmlReportFormatter implements ReportFormatter {

    @Override
    public String format(Map<String, ComparisonResult> comparisons,
                        Map<String, OverheadAnalyzer.OverheadAnalysis> analyses) {
        StringBuilder sb = new StringBuilder();

        // HTML header
        sb.append("<!DOCTYPE html>\n");
        sb.append("<html lang=\"en\">\n");
        sb.append("<head>\n");
        sb.append("    <meta charset=\"UTF-8\">\n");
        sb.append("    <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n");
        sb.append("    <title>JVector vs Cassandra Benchmark Comparison</title>\n");
        sb.append("    <style>\n");
        sb.append(getStyles());
        sb.append("    </style>\n");
        sb.append("</head>\n");
        sb.append("<body>\n");

        // Content
        sb.append("    <div class=\"container\">\n");
        sb.append("        <h1>JVector vs Cassandra Benchmark Comparison</h1>\n");
        sb.append("        <p class=\"timestamp\">Generated: ")
          .append(LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME))
          .append("</p>\n");
        sb.append("        <hr>\n\n");

        // For each dataset
        for (Map.Entry<String, ComparisonResult> entry : comparisons.entrySet()) {
            String dataset = entry.getKey();
            ComparisonResult comparison = entry.getValue();

            sb.append("        <div class=\"dataset-section\">\n");
            sb.append("            <h2>Dataset: ").append(dataset).append("</h2>\n\n");

            // Metrics table
            sb.append("            <h3>Performance Metrics</h3>\n");
            sb.append("            <table>\n");
            sb.append("                <thead>\n");
            sb.append("                    <tr>\n");
            sb.append("                        <th>Metric</th>\n");
            sb.append("                        <th>JVector Direct</th>\n");
            sb.append("                        <th>Cassandra</th>\n");
            sb.append("                        <th>Difference</th>\n");
            sb.append("                        <th>Overhead</th>\n");
            sb.append("                        <th>Status</th>\n");
            sb.append("                    </tr>\n");
            sb.append("                </thead>\n");
            sb.append("                <tbody>\n");

            for (ComparisonResult.MetricComparison metric : comparison.getMetrics().values()) {
                String statusClass = getStatusClass(metric.getOverheadPercentage(),
                    metric.getOverheadType());

                sb.append("                    <tr class=\"").append(statusClass).append("\">\n");
                sb.append(String.format("                        <td><strong>%s</strong></td>\n",
                    metric.getName()));
                sb.append(String.format("                        <td>%.2f</td>\n",
                    metric.getJvectorValue()));
                sb.append(String.format("                        <td>%.2f</td>\n",
                    metric.getCassandraValue()));
                sb.append(String.format("                        <td>%+.2f</td>\n",
                    metric.getAbsoluteDifference()));
                sb.append(String.format("                        <td class=\"overhead\">%+.1f%%</td>\n",
                    metric.getOverheadPercentage()));
                sb.append(String.format("                        <td>%s</td>\n",
                    escapeHtml(metric.getInterpretation())));
                sb.append("                    </tr>\n");
            }

            sb.append("                </tbody>\n");
            sb.append("            </table>\n\n");

            // Overhead analysis
            if (analyses.containsKey(dataset)) {
                OverheadAnalyzer.OverheadAnalysis analysis = analyses.get(dataset);

                sb.append("            <h3>Overhead Analysis</h3>\n");

                if (!analysis.getSources().isEmpty()) {
                    sb.append("            <h4>Estimated Overhead Sources</h4>\n");
                    sb.append("            <table class=\"overhead-table\">\n");
                    sb.append("                <thead>\n");
                    sb.append("                    <tr><th>Source</th><th>Estimated %</th></tr>\n");
                    sb.append("                </thead>\n");
                    sb.append("                <tbody>\n");

                    double total = 0;
                    for (OverheadAnalyzer.OverheadSource source : analysis.getSources()) {
                        sb.append(String.format("                    <tr><td>%s</td><td>~%.1f%%</td></tr>\n",
                            source.getName(), source.getPercentage()));
                        total += source.getPercentage();
                    }

                    sb.append(String.format("                    <tr class=\"total\"><td><strong>Total</strong></td><td><strong>~%.1f%%</strong></td></tr>\n",
                        total));
                    sb.append("                </tbody>\n");
                    sb.append("            </table>\n");
                }

                if (!analysis.getRecommendations().isEmpty()) {
                    sb.append("            <h4>Recommendations</h4>\n");
                    sb.append("            <ul class=\"recommendations\">\n");
                    for (String rec : analysis.getRecommendations()) {
                        sb.append("                <li>").append(escapeHtml(rec)).append("</li>\n");
                    }
                    sb.append("            </ul>\n");
                }
            }

            sb.append("        </div>\n");
            sb.append("        <hr>\n\n");
        }

        // Summary
        sb.append("        <div class=\"summary-section\">\n");
        sb.append("            <h2>Summary</h2>\n");
        sb.append("            <p>This report compares jvector-direct performance with Cassandra integration performance. ");
        sb.append("The overhead represents the additional cost of going through Cassandra's full stack ");
        sb.append("(network, CQL, storage layer, etc.) versus calling jvector directly.</p>\n");
        sb.append("            <h3>Key Points</h3>\n");
        sb.append("            <ul>\n");
        sb.append("                <li><strong>Throughput (QPS):</strong> Lower is worse, negative overhead indicates slower performance</li>\n");
        sb.append("                <li><strong>Latency:</strong> Higher is worse, positive overhead indicates slower performance</li>\n");
        sb.append("                <li><strong>Accuracy (Recall):</strong> Should be identical - any difference indicates configuration mismatch</li>\n");
        sb.append("                <li><strong>Expected Overhead:</strong> 20-40% slower QPS, 15-50% higher latency for local single-node clusters</li>\n");
        sb.append("            </ul>\n");
        sb.append("        </div>\n");

        sb.append("    </div>\n");
        sb.append("</body>\n");
        sb.append("</html>\n");

        return sb.toString();
    }

    private String getStyles() {
        return """
            body {
                font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Oxygen, Ubuntu, Cantarell, sans-serif;
                line-height: 1.6;
                color: #333;
                max-width: 1200px;
                margin: 0 auto;
                padding: 20px;
                background-color: #f5f5f5;
            }
            .container {
                background: white;
                padding: 30px;
                border-radius: 8px;
                box-shadow: 0 2px 4px rgba(0,0,0,0.1);
            }
            h1 {
                color: #2c3e50;
                border-bottom: 3px solid #3498db;
                padding-bottom: 10px;
            }
            h2 {
                color: #34495e;
                margin-top: 30px;
            }
            h3 {
                color: #7f8c8d;
            }
            .timestamp {
                color: #95a5a6;
                font-size: 0.9em;
            }
            table {
                width: 100%;
                border-collapse: collapse;
                margin: 20px 0;
            }
            th {
                background-color: #3498db;
                color: white;
                padding: 12px;
                text-align: left;
                font-weight: 600;
            }
            td {
                padding: 10px 12px;
                border-bottom: 1px solid #ecf0f1;
            }
            tr:hover {
                background-color: #f8f9fa;
            }
            tr.good {
                background-color: #d5f4e6;
            }
            tr.warning {
                background-color: #fff3cd;
            }
            tr.critical {
                background-color: #f8d7da;
            }
            .overhead {
                font-weight: bold;
            }
            .overhead-table {
                width: 50%;
            }
            .total {
                background-color: #e8f4f8;
                font-weight: bold;
            }
            .recommendations {
                background-color: #f0f8ff;
                padding: 15px;
                border-left: 4px solid #3498db;
                list-style-position: inside;
            }
            .recommendations li {
                margin: 8px 0;
            }
            hr {
                border: none;
                border-top: 2px solid #ecf0f1;
                margin: 30px 0;
            }
            .dataset-section {
                margin: 30px 0;
            }
            .summary-section {
                margin-top: 40px;
                padding: 20px;
                background-color: #f8f9fa;
                border-radius: 6px;
            }
            """;
    }

    private String getStatusClass(double overhead, ComparisonResult.OverheadType type) {
        if (type == ComparisonResult.OverheadType.ACCURACY) {
            if (Math.abs(overhead) < 1.0) return "good";
            if (Math.abs(overhead) < 5.0) return "warning";
            return "critical";
        }

        double absOverhead = Math.abs(overhead);
        if (absOverhead < 15) return "good";
        if (absOverhead < 30) return "warning";
        return "critical";
    }

    private String escapeHtml(String text) {
        return text.replace("&", "&amp;")
                   .replace("<", "&lt;")
                   .replace(">", "&gt;")
                   .replace("\"", "&quot;")
                   .replace("'", "&#39;");
    }

    @Override
    public String getFileExtension() {
        return "html";
    }
}

