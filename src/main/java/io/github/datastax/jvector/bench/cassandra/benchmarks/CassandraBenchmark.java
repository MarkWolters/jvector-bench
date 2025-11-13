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

import java.util.List;

/**
 * Common interface for all Cassandra benchmarks.
 */
public interface CassandraBenchmark {
    /**
     * Get the name of this benchmark.
     */
    String getBenchmarkName();

    /**
     * Run the benchmark and return metrics.
     *
     * @param connection  Connection to Cassandra cluster
     * @param ds          Dataset with vectors and queries
     * @param topK        Number of results to return
     * @param searchConfig Search configuration
     * @param queryRuns   Number of times to run the full query set
     * @return List of metrics collected
     */
    List<Metric> runBenchmark(
        CassandraConnection connection,
        DataSet ds,
        int topK,
        SearchConfig searchConfig,
        int queryRuns
    );
}
