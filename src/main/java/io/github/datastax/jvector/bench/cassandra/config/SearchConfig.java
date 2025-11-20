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
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Configuration for search operations against Cassandra.
 */
public class SearchConfig {
    private String similarityFunction = "DOT_PRODUCT";
    private DefaultConsistencyLevel readConsistency = DefaultConsistencyLevel.ONE;
    private int queryTimeoutMs = 10000;
    
    @JsonProperty("rerank_k")
    private Integer rerankK;
    
    @JsonProperty("use_pruning")
    private Boolean usePruning;

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
    
    public Integer getRerankK() {
        return rerankK;
    }
    
    public void setRerankK(Integer rerankK) {
        this.rerankK = rerankK;
    }
    
    public Boolean getUsePruning() {
        return usePruning;
    }
    
    public void setUsePruning(Boolean usePruning) {
        this.usePruning = usePruning;
    }
    
    /**
     * Build the ANN options clause for CQL queries.
     * Returns null if no ANN options are set.
     */
    public String buildAnnOptionsClause() {
        if (rerankK == null && usePruning == null) {
            return null;
        }
        
        StringBuilder sb = new StringBuilder(" WITH ann_options = {");
        boolean first = true;
        
        if (rerankK != null) {
            sb.append("'rerank_k': ").append(rerankK);
            first = false;
        }
        
        if (usePruning != null) {
            if (!first) {
                sb.append(", ");
            }
            sb.append("'use_pruning': ").append(usePruning);
        }
        
        sb.append("}");
        return sb.toString();
    }
}
