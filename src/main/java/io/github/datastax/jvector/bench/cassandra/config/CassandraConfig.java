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
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Configuration for connecting to a Cassandra cluster.
 * Can be loaded from YAML file or built programmatically.
 */
public class CassandraConfig {
    @JsonProperty("contact_points")
    private List<String> contactPoints;

    @JsonProperty("local_datacenter")
    private String localDatacenter;

    @JsonProperty("keyspace")
    private String keyspace = "jvector_bench";

    @JsonProperty("replication_factor")
    private int replicationFactor = 1;

    @JsonProperty("replication_strategy")
    private String replicationStrategy = "SimpleStrategy";

    @JsonProperty("replication_config")
    private Map<String, Integer> replicationConfig;

    @JsonProperty("username")
    private String username;

    @JsonProperty("password")
    private String password;

    @JsonProperty("ssl")
    private SslConfig ssl;

    @JsonProperty("connection")
    private ConnectionConfig connection;

    @JsonProperty("write_consistency")
    private String writeConsistency = "ONE";

    @JsonProperty("read_consistency")
    private String readConsistency = "ONE";

    @JsonProperty("retry_policy")
    private String retryPolicy = "DEFAULT";

    /**
     * Load configuration from YAML file
     */
    public static CassandraConfig fromYaml(String path) throws IOException {
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        File file = new File(path);

        // YAML may have a "cassandra:" root key
        Map<String, Object> yaml = mapper.readValue(file, Map.class);
        if (yaml.containsKey("cassandra")) {
            // Extract nested config
            return mapper.convertValue(yaml.get("cassandra"), CassandraConfig.class);
        } else {
            // Direct config
            return mapper.convertValue(yaml, CassandraConfig.class);
        }
    }

    /**
     * Create default configuration for localhost
     */
    public static CassandraConfig defaultLocalhost() {
        CassandraConfig config = new CassandraConfig();
        config.contactPoints = List.of("127.0.0.1:9042");
        config.localDatacenter = "datacenter1";
        config.keyspace = "jvector_bench";
        config.replicationFactor = 1;
        return config;
    }

    // Getters and setters

    public List<String> getContactPoints() {
        return contactPoints;
    }

    public void setContactPoints(List<String> contactPoints) {
        this.contactPoints = contactPoints;
    }

    public String getLocalDatacenter() {
        return localDatacenter;
    }

    public void setLocalDatacenter(String localDatacenter) {
        this.localDatacenter = localDatacenter;
    }

    public String getKeyspace() {
        return keyspace;
    }

    public void setKeyspace(String keyspace) {
        this.keyspace = keyspace;
    }

    public int getReplicationFactor() {
        return replicationFactor;
    }

    public void setReplicationFactor(int replicationFactor) {
        this.replicationFactor = replicationFactor;
    }

    public String getReplicationStrategy() {
        return replicationStrategy;
    }

    public void setReplicationStrategy(String replicationStrategy) {
        this.replicationStrategy = replicationStrategy;
    }

    public Map<String, Integer> getReplicationConfig() {
        return replicationConfig;
    }

    public void setReplicationConfig(Map<String, Integer> replicationConfig) {
        this.replicationConfig = replicationConfig;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public boolean hasAuthentication() {
        return username != null && password != null;
    }

    public SslConfig getSsl() {
        return ssl;
    }

    public void setSsl(SslConfig ssl) {
        this.ssl = ssl;
    }

    public boolean isSslEnabled() {
        return ssl != null && ssl.enabled;
    }

    public ConnectionConfig getConnection() {
        return connection;
    }

    public void setConnection(ConnectionConfig connection) {
        this.connection = connection;
    }

    public String getWriteConsistency() {
        return writeConsistency;
    }

    public void setWriteConsistency(String writeConsistency) {
        this.writeConsistency = writeConsistency;
    }

    public DefaultConsistencyLevel getWriteConsistencyLevel() {
        return DefaultConsistencyLevel.valueOf(writeConsistency);
    }

    public String getReadConsistency() {
        return readConsistency;
    }

    public void setReadConsistency(String readConsistency) {
        this.readConsistency = readConsistency;
    }

    public DefaultConsistencyLevel getReadConsistencyLevel() {
        return DefaultConsistencyLevel.valueOf(readConsistency);
    }

    public String getRetryPolicy() {
        return retryPolicy;
    }

    public void setRetryPolicy(String retryPolicy) {
        this.retryPolicy = retryPolicy;
    }

    /**
     * Get replication strategy string for CREATE KEYSPACE
     */
    public String getReplicationStrategyString() {
        if ("NetworkTopologyStrategy".equals(replicationStrategy)) {
            if (replicationConfig == null || replicationConfig.isEmpty()) {
                throw new IllegalStateException("NetworkTopologyStrategy requires replication_config");
            }
            StringBuilder sb = new StringBuilder("{'class': 'NetworkTopologyStrategy'");
            for (Map.Entry<String, Integer> entry : replicationConfig.entrySet()) {
                sb.append(", '").append(entry.getKey()).append("': ").append(entry.getValue());
            }
            sb.append("}");
            return sb.toString();
        } else {
            return String.format("{'class': 'SimpleStrategy', 'replication_factor': %d}", replicationFactor);
        }
    }

    /**
     * SSL configuration
     */
    public static class SslConfig {
        @JsonProperty("enabled")
        private boolean enabled = false;

        @JsonProperty("truststore_path")
        private String truststorePath;

        @JsonProperty("truststore_password")
        private String truststorePassword;

        @JsonProperty("keystore_path")
        private String keystorePath;

        @JsonProperty("keystore_password")
        private String keystorePassword;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getTruststorePath() {
            return truststorePath;
        }

        public void setTruststorePath(String truststorePath) {
            this.truststorePath = truststorePath;
        }

        public String getTruststorePassword() {
            return truststorePassword;
        }

        public void setTruststorePassword(String truststorePassword) {
            this.truststorePassword = truststorePassword;
        }

        public String getKeystorePath() {
            return keystorePath;
        }

        public void setKeystorePath(String keystorePath) {
            this.keystorePath = keystorePath;
        }

        public String getKeystorePassword() {
            return keystorePassword;
        }

        public void setKeystorePassword(String keystorePassword) {
            this.keystorePassword = keystorePassword;
        }
    }

    /**
     * Connection pool configuration
     */
    public static class ConnectionConfig {
        @JsonProperty("max_requests_per_connection")
        private int maxRequestsPerConnection = 1024;

        @JsonProperty("pool_local_size")
        private int poolLocalSize = 4;

        @JsonProperty("pool_remote_size")
        private int poolRemoteSize = 2;

        @JsonProperty("connect_timeout_ms")
        private int connectTimeoutMs = 5000;

        @JsonProperty("read_timeout_ms")
        private int readTimeoutMs = 12000;

        public int getMaxRequestsPerConnection() {
            return maxRequestsPerConnection;
        }

        public void setMaxRequestsPerConnection(int maxRequestsPerConnection) {
            this.maxRequestsPerConnection = maxRequestsPerConnection;
        }

        public int getPoolLocalSize() {
            return poolLocalSize;
        }

        public void setPoolLocalSize(int poolLocalSize) {
            this.poolLocalSize = poolLocalSize;
        }

        public int getPoolRemoteSize() {
            return poolRemoteSize;
        }

        public void setPoolRemoteSize(int poolRemoteSize) {
            this.poolRemoteSize = poolRemoteSize;
        }

        public int getConnectTimeoutMs() {
            return connectTimeoutMs;
        }

        public void setConnectTimeoutMs(int connectTimeoutMs) {
            this.connectTimeoutMs = connectTimeoutMs;
        }

        public int getReadTimeoutMs() {
            return readTimeoutMs;
        }

        public void setReadTimeoutMs(int readTimeoutMs) {
            this.readTimeoutMs = readTimeoutMs;
        }
    }
}
