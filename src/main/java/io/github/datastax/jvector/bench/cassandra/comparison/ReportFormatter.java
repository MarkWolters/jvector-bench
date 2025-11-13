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

import java.io.IOException;
import java.util.Map;

/**
 * Interface for formatting comparison reports in different formats.
 */
public interface ReportFormatter {
    /**
     * Format comparison results into a report.
     *
     * @param comparisons Map of dataset names to comparison results
     * @param analyses Map of dataset names to overhead analyses
     * @return Formatted report as string
     */
    String format(Map<String, ComparisonResult> comparisons,
                  Map<String, OverheadAnalyzer.OverheadAnalysis> analyses) throws IOException;

    /**
     * Get the file extension for this format.
     */
    String getFileExtension();
}
