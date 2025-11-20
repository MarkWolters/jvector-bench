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

/**
 * Configuration for loading datasets into Cassandra.
 */
public class LoadingConfig {
    @JsonProperty("batch_size")
    private int batchSize = 500;

    @JsonProperty("concurrency")
    private int concurrency = 32;

    @JsonProperty("logged")
    private boolean logged = false;

    @JsonProperty("skip_index_wait")
    private boolean skipIndexWait = false;

    @JsonProperty("drop_existing")
    private boolean dropExisting = false;

    @JsonProperty("enable_backpressure")
    private boolean enableBackpressure = true;

    @JsonProperty("backpressure_error_threshold")
    private double backpressureErrorThreshold = 0.1;

    @JsonProperty("backpressure_window_size")
    private int backpressureWindowSize = 100;

    @JsonProperty("backpressure_min_delay_ms")
    private long backpressureMinDelayMs = 0;

    @JsonProperty("backpressure_max_delay_ms")
    private long backpressureMaxDelayMs = 5000;

    public LoadingConfig() {
    }

    public LoadingConfig(int batchSize, int concurrency) {
        this.batchSize = batchSize;
        this.concurrency = concurrency;
    }

    /**
     * Create default configuration
     */
    public static LoadingConfig defaults() {
        return new LoadingConfig();
    }

    /**
     * Create configuration optimized for small datasets
     */
    public static LoadingConfig forSmallDataset() {
        return new LoadingConfig(100, 16);
    }

    /**
     * Create configuration optimized for large datasets
     */
    public static LoadingConfig forLargeDataset() {
        return new LoadingConfig(1000, 64);
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public int getConcurrency() {
        return concurrency;
    }

    public void setConcurrency(int concurrency) {
        this.concurrency = concurrency;
    }

    public boolean isLogged() {
        return logged;
    }

    public void setLogged(boolean logged) {
        this.logged = logged;
    }

    public boolean isSkipIndexWait() {
        return skipIndexWait;
    }

    public void setSkipIndexWait(boolean skipIndexWait) {
        this.skipIndexWait = skipIndexWait;
    }

    public boolean isDropExisting() {
        return dropExisting;
    }

    public void setDropExisting(boolean dropExisting) {
        this.dropExisting = dropExisting;
    }

    public boolean isEnableBackpressure() {
        return enableBackpressure;
    }

    public void setEnableBackpressure(boolean enableBackpressure) {
        this.enableBackpressure = enableBackpressure;
    }

    public double getBackpressureErrorThreshold() {
        return backpressureErrorThreshold;
    }

    public void setBackpressureErrorThreshold(double backpressureErrorThreshold) {
        this.backpressureErrorThreshold = backpressureErrorThreshold;
    }

    public int getBackpressureWindowSize() {
        return backpressureWindowSize;
    }

    public void setBackpressureWindowSize(int backpressureWindowSize) {
        this.backpressureWindowSize = backpressureWindowSize;
    }

    public long getBackpressureMinDelayMs() {
        return backpressureMinDelayMs;
    }

    public void setBackpressureMinDelayMs(long backpressureMinDelayMs) {
        this.backpressureMinDelayMs = backpressureMinDelayMs;
    }

    public long getBackpressureMaxDelayMs() {
        return backpressureMaxDelayMs;
    }

    public void setBackpressureMaxDelayMs(long backpressureMaxDelayMs) {
        this.backpressureMaxDelayMs = backpressureMaxDelayMs;
    }

    @Override
    public String toString() {
        return String.format("LoadingConfig{batchSize=%d, concurrency=%d, logged=%s, backpressure=%s}",
            batchSize, concurrency, logged, enableBackpressure);
    }
}
