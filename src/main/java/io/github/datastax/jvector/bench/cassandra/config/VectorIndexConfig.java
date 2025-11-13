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

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.File;
import java.io.IOException;
import java.util.Map;

/**
 * Configuration for vector index creation in Cassandra.
 * Maps to SAI (Storage Attached Index) vector index options.
 */
public class VectorIndexConfig {
    @JsonProperty("dimension")
    private int dimension;

    @JsonProperty("similarity_function")
    private String similarityFunction = "DOT_PRODUCT";

    @JsonProperty("maximum_node_connections")
    private int maximumNodeConnections = 16;

    @JsonProperty("construction_beam_width")
    private int constructionBeamWidth = 100;

    @JsonProperty("source_model")
    private String sourceModel = "OTHER";

    @JsonProperty("enable_hierarchy")
    private boolean enableHierarchy = false;

    /**
     * Load configuration from YAML file
     */
    public static VectorIndexConfig fromYaml(String path) throws IOException {
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        File file = new File(path);

        Map<String, Object> yaml = mapper.readValue(file, Map.class);
        if (yaml.containsKey("vector_index")) {
            return mapper.convertValue(yaml.get("vector_index"), VectorIndexConfig.class);
        } else {
            return mapper.convertValue(yaml, VectorIndexConfig.class);
        }
    }

    /**
     * Create default configuration for OpenAI ada-002
     */
    public static VectorIndexConfig defaultAda002() {
        VectorIndexConfig config = new VectorIndexConfig();
        config.dimension = 1536;
        config.similarityFunction = "DOT_PRODUCT";
        config.sourceModel = "ADA002";
        return config;
    }

    /**
     * Create default configuration for OpenAI v3 large
     */
    public static VectorIndexConfig defaultOpenAIV3Large() {
        VectorIndexConfig config = new VectorIndexConfig();
        config.dimension = 3072;
        config.similarityFunction = "DOT_PRODUCT";
        config.sourceModel = "OPENAI_V3_LARGE";
        return config;
    }

    /**
     * Create default configuration for Cohere v3
     */
    public static VectorIndexConfig defaultCohereV3() {
        VectorIndexConfig config = new VectorIndexConfig();
        config.dimension = 1024;
        config.similarityFunction = "DOT_PRODUCT";
        config.sourceModel = "COHERE_V3";
        return config;
    }

    /**
     * Generate CQL WITH OPTIONS clause for CREATE INDEX
     */
    public String toCqlOptions() {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  'similarity_function': '").append(similarityFunction).append("',\n");
        sb.append("  'maximum_node_connections': ").append(maximumNodeConnections).append(",\n");
        sb.append("  'construction_beam_width': ").append(constructionBeamWidth).append(",\n");
        sb.append("  'source_model': '").append(sourceModel).append("'");
        if (enableHierarchy) {
            sb.append(",\n  'enable_hierarchy': true");
        }
        sb.append("\n}");
        return sb.toString();
    }

    // Getters and setters

    public int getDimension() {
        return dimension;
    }

    public void setDimension(int dimension) {
        this.dimension = dimension;
    }

    public String getSimilarityFunction() {
        return similarityFunction;
    }

    public void setSimilarityFunction(String similarityFunction) {
        this.similarityFunction = similarityFunction;
    }

    public int getMaximumNodeConnections() {
        return maximumNodeConnections;
    }

    public void setMaximumNodeConnections(int maximumNodeConnections) {
        this.maximumNodeConnections = maximumNodeConnections;
    }

    public int getConstructionBeamWidth() {
        return constructionBeamWidth;
    }

    public void setConstructionBeamWidth(int constructionBeamWidth) {
        this.constructionBeamWidth = constructionBeamWidth;
    }

    public String getSourceModel() {
        return sourceModel;
    }

    public void setSourceModel(String sourceModel) {
        this.sourceModel = sourceModel;
    }

    public boolean isEnableHierarchy() {
        return enableHierarchy;
    }

    public void setEnableHierarchy(boolean enableHierarchy) {
        this.enableHierarchy = enableHierarchy;
    }

    @Override
    public String toString() {
        return String.format("VectorIndexConfig{dimension=%d, similarity=%s, M=%d, efConstruction=%d, model=%s}",
            dimension, similarityFunction, maximumNodeConnections, constructionBeamWidth, sourceModel);
    }
}
