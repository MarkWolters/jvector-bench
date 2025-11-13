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
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

/**
 * Formats comparison reports as JSON.
 */
public class JsonReportFormatter implements ReportFormatter {

    @Override
    public String format(Map<String, ComparisonResult> comparisons,
                        Map<String, OverheadAnalyzer.OverheadAnalysis> analyses) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        mapper.enable(SerializationFeature.INDENT_OUTPUT);

        Map<String, Object> report = new HashMap<>();
        report.put("generated", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        report.put("type", "jvector-vs-cassandra-comparison");

        Map<String, Object> datasets = new HashMap<>();

        for (Map.Entry<String, ComparisonResult> entry : comparisons.entrySet()) {
            String dataset = entry.getKey();
            ComparisonResult comparison = entry.getValue();

            Map<String, Object> datasetReport = new HashMap<>();

            // Metrics
            Map<String, Object> metrics = new HashMap<>();
            for (ComparisonResult.MetricComparison metric : comparison.getMetrics().values()) {
                Map<String, Object> metricData = new HashMap<>();
                metricData.put("jvector_value", metric.getJvectorValue());
                metricData.put("cassandra_value", metric.getCassandraValue());
                metricData.put("absolute_difference", metric.getAbsoluteDifference());
                metricData.put("percentage_difference", metric.getPercentageDifference());
                metricData.put("overhead_percentage", metric.getOverheadPercentage());
                metricData.put("type", metric.getOverheadType().toString());
                metricData.put("interpretation", metric.getInterpretation());
                metricData.put("is_significant", metric.isSignificant(10.0));

                metrics.put(metric.getName(), metricData);
            }
            datasetReport.put("metrics", metrics);

            // Analysis
            if (analyses.containsKey(dataset)) {
                OverheadAnalyzer.OverheadAnalysis analysis = analyses.get(dataset);

                Map<String, Object> analysisData = new HashMap<>();

                // Overhead sources
                Map<String, Double> sources = new HashMap<>();
                for (OverheadAnalyzer.OverheadSource source : analysis.getSources()) {
                    sources.put(source.getName(), source.getPercentage());
                }
                analysisData.put("overhead_sources", sources);

                // Recommendations
                analysisData.put("recommendations", analysis.getRecommendations());

                datasetReport.put("analysis", analysisData);
            }

            // Metadata
            datasetReport.put("metadata", comparison.getMetadata());

            datasets.put(dataset, datasetReport);
        }

        report.put("datasets", datasets);

        return mapper.writeValueAsString(report);
    }

    @Override
    public String getFileExtension() {
        return "json";
    }
}
