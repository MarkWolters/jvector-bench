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

package io.github.datastax.jvector.bench.cassandra.config;

import com.datastax.oss.driver.api.core.DefaultConsistencyLevel;

/**
 * Configuration for search operations against Cassandra.
 */
public class SearchConfig {
    private String similarityFunction = "DOT_PRODUCT";
    private DefaultConsistencyLevel readConsistency = DefaultConsistencyLevel.ONE;
    private int queryTimeoutMs = 10000;

    public SearchConfig() {
    }

    public SearchConfig(String similarityFunction) {
        this.similarityFunction = similarityFunction;
    }

    public static SearchConfig fromVectorIndexConfig(VectorIndexConfig indexConfig) {
        SearchConfig config = new SearchConfig();
        config.similarityFunction = indexConfig.getSimilarityFunction();
        return config;
    }

    public static SearchConfig withConsistency(DefaultConsistencyLevel consistency) {
        SearchConfig config = new SearchConfig();
        config.readConsistency = consistency;
        return config;
    }

    public String getSimilarityFunction() {
        return similarityFunction;
    }

    public void setSimilarityFunction(String similarityFunction) {
        this.similarityFunction = similarityFunction;
    }

    public DefaultConsistencyLevel getReadConsistency() {
        return readConsistency;
    }

    public void setReadConsistency(DefaultConsistencyLevel readConsistency) {
        this.readConsistency = readConsistency;
    }

    public int getQueryTimeoutMs() {
        return queryTimeoutMs;
    }

    public void setQueryTimeoutMs(int queryTimeoutMs) {
        this.queryTimeoutMs = queryTimeoutMs;
    }
}
