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

package io.github.datastax.jvector.bench.cassandra;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.CqlSessionBuilder;
import com.datastax.oss.driver.api.core.cql.*;
import com.datastax.oss.driver.api.core.data.CqlVector;
import com.datastax.oss.driver.api.core.metadata.Metadata;
import io.github.datastax.jvector.bench.cassandra.config.CassandraConfig;
import io.github.datastax.jvector.bench.cassandra.config.SearchConfig;
import io.github.datastax.jvector.bench.cassandra.config.VectorIndexConfig;
import io.github.jbellis.jvector.graph.SearchResult;
import io.github.jbellis.jvector.vector.types.VectorFloat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Manages connection to a Cassandra cluster and provides methods for
 * schema management, data loading, and vector search operations.
 */
public class CassandraConnection implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(CassandraConnection.class);

    private final CqlSession session;
    private final CassandraConfig config;
    private PreparedStatement searchStatement;

    private CassandraConnection(CqlSession session, CassandraConfig config) {
        this.session = session;
        this.config = config;
    }

    /**
     * Connect to a Cassandra cluster using the provided configuration.
     */
    public static CassandraConnection connect(CassandraConfig config) {
        logger.info("Connecting to Cassandra cluster...");

        CqlSessionBuilder builder = CqlSession.builder();

        // Add contact points
        for (String contactPoint : config.getContactPoints()) {
            String[] parts = contactPoint.split(":");
            String host = parts[0];
            int port = parts.length > 1 ? Integer.parseInt(parts[1]) : 9042;
            builder.addContactPoint(new InetSocketAddress(host, port));
            logger.debug("Added contact point: {}:{}", host, port);
        }

        builder.withLocalDatacenter(config.getLocalDatacenter());

        // Optional authentication
        if (config.hasAuthentication()) {
            builder.withAuthCredentials(config.getUsername(), config.getPassword());
            logger.debug("Using authentication for user: {}", config.getUsername());
        }

        // Optional connection pool configuration
        if (config.getConnection() != null) {
            var connConfig = config.getConnection();
            // Driver 4.x uses programmatic config or application.conf
            // For simplicity, we'll use defaults unless absolutely needed
            logger.debug("Connection pool config: local={}, remote={}",
                connConfig.getPoolLocalSize(), connConfig.getPoolRemoteSize());
        }

        CqlSession session = builder.build();

        // Log cluster information
        Metadata metadata = session.getMetadata();
        logger.info("Connected to cluster: {} [{}]",
            metadata.getClusterName().orElse("unknown"),
            metadata.getNodes().keySet());

        return new CassandraConnection(session, config);
    }

    /**
     * Ensure schema (keyspace, table, index) exists.
     * If dropExisting is true, drops and recreates everything.
     */
    public void ensureSchema(VectorIndexConfig indexConfig, boolean dropExisting) {
        logger.info("Setting up schema...");

        if (dropExisting) {
            logger.info("Dropping existing schema...");
            session.execute(String.format("DROP KEYSPACE IF EXISTS %s", config.getKeyspace()));
        }

        // Create keyspace
        String createKeyspace = String.format(
            "CREATE KEYSPACE IF NOT EXISTS %s WITH replication = %s",
            config.getKeyspace(),
            config.getReplicationStrategyString()
        );
        logger.debug("Creating keyspace: {}", createKeyspace);
        session.execute(createKeyspace);

        // Use keyspace
        session.execute("USE " + config.getKeyspace());
        logger.info("Using keyspace: {}", config.getKeyspace());

        // Create table
        String createTable = String.format(
            "CREATE TABLE IF NOT EXISTS vectors (id text PRIMARY KEY, vector vector<float, %d>)",
            indexConfig.getDimension()
        );
        logger.debug("Creating table: {}", createTable);
        session.execute(createTable);

        // Create index
        String createIndex = String.format(
            "CREATE CUSTOM INDEX IF NOT EXISTS vectors_ann_idx ON vectors(vector) " +
            "USING 'StorageAttachedIndex' WITH OPTIONS = %s",
            indexConfig.toCqlOptions()
        );
        logger.debug("Creating index: {}", createIndex);
        session.execute(createIndex);

        logger.info("Schema ready with config: {}", indexConfig);
    }

    /**
     * Wait for vector index to be built and ready.
     * Polls system tables until index is available.
     */
    public void waitForIndexBuild() {
        logger.info("Waiting for index build to complete...");

        // Query to check if index exists
        String checkIndex = "SELECT index_name FROM system_schema.indexes " +
                           "WHERE keyspace_name = ? AND table_name = ?";

        PreparedStatement ps = session.prepare(checkIndex);

        int attempts = 0;
        int maxAttempts = 300; // 5 minutes with 1 second sleep

        while (attempts < maxAttempts) {
            ResultSet rs = session.execute(ps.bind(config.getKeyspace(), "vectors"));
            Row row = rs.one();

            if (row != null) {
                String indexName = row.getString("index_name");
                if (indexName != null) {
                    logger.info("Index '{}' is ready", indexName);
                    return;
                }
            }

            attempts++;
            try {
                Thread.sleep(1000);
                if (attempts % 10 == 0) {
                    logger.info("Still waiting for index build... ({} seconds)", attempts);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted while waiting for index", e);
            }
        }

        throw new RuntimeException("Timeout waiting for index build after " + maxAttempts + " seconds");
    }

    /**
     * Prepare the search statement for repeated execution.
     */
    public void prepareSearch(SearchConfig searchConfig) {
        String cql = String.format(
            "SELECT id, vector, similarity_%s(vector, ?) as score " +
            "FROM %s.vectors " +
            "ORDER BY vector ANN OF ? " +
            "LIMIT ?",
            searchConfig.getSimilarityFunction().toLowerCase(),
            config.getKeyspace()
        );

        this.searchStatement = session.prepare(cql);
        logger.debug("Prepared search statement: {}", cql);
    }

    /**
     * Execute a vector similarity search.
     */
    public SearchResult search(VectorFloat<?> query, int topK, SearchConfig searchConfig) {
        if (searchStatement == null) {
            prepareSearch(searchConfig);
        }

        List<Float> queryList = vectorToList(query);
        CqlVector<Float> queryVector = CqlVector.newInstance(queryList);

        BoundStatement bound = searchStatement.bind(queryVector, queryVector, topK)
            .setConsistencyLevel(searchConfig.getReadConsistency())
            .setTimeout(Duration.ofMillis(searchConfig.getQueryTimeoutMs()));

        ResultSet rs = session.execute(bound);

        return convertToSearchResult(rs);
    }

    /**
     * Get the CQL session for advanced operations.
     */
    public CqlSession getSession() {
        return session;
    }

    /**
     * Get the configuration used for this connection.
     */
    public CassandraConfig getConfig() {
        return config;
    }

    /**
     * Convert VectorFloat to List<Float> for CQL binding.
     */
    private List<Float> vectorToList(VectorFloat<?> vector) {
        List<Float> list = new ArrayList<>(vector.length());
        for (int i = 0; i < vector.length(); i++) {
            list.add(vector.get(i));
        }
        return list;
    }

    /**
     * Convert Cassandra ResultSet to jvector SearchResult format.
     * This allows us to reuse existing AccuracyMetrics code.
     *
     * Note: Cassandra doesn't expose internal jvector search metrics through CQL,
     * so we use -1 for unknown values (visitedCount, expandedCount, etc.)
     */
    private SearchResult convertToSearchResult(ResultSet rs) {
        List<SearchResult.NodeScore> nodes = new ArrayList<>();
        float worstScore = Float.POSITIVE_INFINITY;

        for (Row row : rs) {
            int id = Integer.parseInt(row.getString("id"));
            float score = row.getFloat("score");
            nodes.add(new SearchResult.NodeScore(id, score));

            // Track the worst (lowest) score for worstApproximateScoreInTopK
            if (score < worstScore) {
                worstScore = score;
            }
        }

        // SearchResult expects nodes in descending score order (highest first)
        // Cassandra should return them in correct order via ORDER BY
        SearchResult.NodeScore[] nodeArray = nodes.toArray(new SearchResult.NodeScore[0]);

        // SearchResult constructor expects:
        // (nodes, visitedCount, expandedCount, expandedCountL0, rerankedCount, worstApproximateScoreInTopK)
        // We use -1 for internal metrics that Cassandra doesn't expose
        return new SearchResult(
            nodeArray,
            -1,  // visitedCount - not available from Cassandra
            -1,  // expandedCount - not available from Cassandra
            -1,  // expandedCountL0 - not available from Cassandra
            -1,  // rerankedCount - not available from Cassandra
            nodes.isEmpty() ? -1.0f : worstScore  // Use actual worst score or -1 if no results
        );
    }

    @Override
    public void close() {
        if (session != null && !session.isClosed()) {
            logger.info("Closing Cassandra session");
            session.close();
        }
    }
}
