# Cassandra Benchmark Plan

## Executive Summary

This document outlines a strategy for creating benchmark tests that measure jvector performance when integrated with Cassandra, allowing comparison with direct jvector library benchmarks.

**Key Design Decision:** The benchmark framework connects to an **externally deployed and managed Cassandra cluster** as a client. This provides:

✅ **Separation of Concerns** - Cluster management is independent from benchmarking
✅ **Realistic Testing** - Tests full production stack including network
✅ **Flexibility** - Can test any cluster configuration (single-node, multi-node, remote)
✅ **Repeatability** - Load data once, run benchmarks multiple times
✅ **Production Validation** - Can benchmark existing production clusters

---

## Current State Analysis

### JVector-Bench Framework

The existing jvector-bench framework tests jvector **directly** through its Java API:

**Benchmarks Executed:**
1. **ThroughputBenchmark** - Measures QPS with parallel queries, includes warmup
2. **LatencyBenchmark** - Measures mean, std dev, and p999 latency
3. **AccuracyBenchmark** - Measures Recall@K and MAP@K
4. **CountBenchmark** - Tracks visited node counts

**Test Flow:**
```
DataSet → GraphIndexBuilder.addGraphNode() → OnDiskGraphIndex.write()
  → OnDiskGraphIndex.load() → GraphSearcher.search()
```

### Cassandra Integration Points

Based on the Cassandra codebase analysis, jvector is integrated at these layers:

**Key Components:**
- `VectorMemtableIndex` - Memtable-level indexing
- `CassandraOnHeapGraph` - Wraps GraphIndexBuilder
- `CassandraDiskAnn` - Loads/searches on-disk graphs
- `CompactionGraph` - Rebuilds during compaction
- `V2VectorIndexSearcher` - Query orchestration

**Data Flow:**
```
CQL INSERT → VectorMemtableIndex → CassandraOnHeapGraph.add()
  → GraphIndexBuilder.addGraphNode()

CQL SELECT → V2VectorIndexSearcher → CassandraDiskAnn.search()
  → OnDiskGraphIndex.search() → rerank
```

---

## Benchmark Strategy

### Goal

Create **functionally equivalent** benchmarks that exercise jvector through Cassandra's integration layer, enabling apples-to-apples performance comparison.

### Key Differences to Account For

When testing through Cassandra, we add these layers:

1. **Network Layer** - CQL protocol overhead (if using drivers)
2. **CQL Parsing** - Query parsing and validation
3. **Storage Layer** - Memtables, SSTables, compaction
4. **Filtering** - Cassandra-specific row filtering
5. **Coordination** - Distributed coordination (even single-node)
6. **Serialization** - ByteBuffer conversions

### Cassandra Test Architecture

```
┌─────────────────────────────────────────────────────────┐
│  CassandraBenchmarkRunner (similar to JvectorBench)    │
└─────────────────────────────────────────────────────────┘
                          │
                          ▼
┌─────────────────────────────────────────────────────────┐
│  CassandraTestHarness                                   │
│  - Start embedded Cassandra or connect to cluster      │
│  - Create keyspace/table with vector index             │
│  - Load dataset via CQL                                 │
│  - Manage lifecycle                                     │
└─────────────────────────────────────────────────────────┘
                          │
                          ▼
┌─────────────────────────────────────────────────────────┐
│  Benchmark Implementations                              │
│  - CassandraThroughputBenchmark                        │
│  - CassandraLatencyBenchmark                           │
│  - CassandraAccuracyBenchmark                          │
│  - CassandraCountBenchmark (if metrics exposed)        │
└─────────────────────────────────────────────────────────┘
```

---

## Implementation Approach

### Architecture: External Cassandra Cluster

**Design Principle:** The Cassandra cluster is **completely independent** from the benchmark framework. Users deploy and manage their own cluster, and the benchmark framework connects as a client.

**Benefits:**
- Tests full stack including network layer
- Reflects real-world production behavior
- Can test against different cluster topologies
- Separates cluster management from benchmarking
- Can benchmark existing production clusters
- No embedded dependencies

**Architecture:**

```
┌─────────────────────────────────────────┐
│   User-Managed Cassandra Cluster        │
│   (Deployed independently)               │
│   - Single node or multi-node            │
│   - User controls all configuration      │
│   - Can be local or remote               │
└─────────────────────────────────────────┘
              ↑
              │ DataStax Java Driver
              │ (CQL protocol)
              ↓
┌─────────────────────────────────────────┐
│   CassandraBenchmarkRunner              │
│   (This project)                         │
│   - Connects to cluster                  │
│   - Sets up schema                       │
│   - Loads data                           │
│   - Runs benchmarks                      │
│   - Collects metrics                     │
└─────────────────────────────────────────┘
```

### Connection Configuration

The benchmark framework accepts cluster connection details via configuration file or command line:

**cassandra-connection.yml:**
```yaml
cassandra:
  contact_points:
    - "127.0.0.1:9042"
    - "192.168.1.10:9042"
  local_datacenter: "datacenter1"
  keyspace: "jvector_bench"

  # Optional authentication
  username: "cassandra"
  password: "cassandra"

  # Optional SSL/TLS
  ssl:
    enabled: false
    truststore_path: "/path/to/truststore.jks"
    truststore_password: "password"

  # Connection pool settings
  connection:
    max_requests_per_connection: 1024
    pool_local_size: 2
    pool_remote_size: 1

  # Consistency levels
  write_consistency: "LOCAL_QUORUM"
  read_consistency: "LOCAL_QUORUM"
```

**Command-line usage:**
```bash
# Using config file
java -jar cassandra-bench.jar \
  --connection cassandra-connection.yml \
  --dataset cap-1M \
  --config bench-config.yml \
  --output results/cap-1M

# Using command-line args
java -jar cassandra-bench.jar \
  --host 127.0.0.1:9042 \
  --datacenter datacenter1 \
  --keyspace jvector_bench \
  --dataset cap-1M \
  --output results/cap-1M
```

### Implementation

```java
public class CassandraConnection implements AutoCloseable {
    private final CqlSession session;
    private final CassandraConfig config;

    public static CassandraConnection connect(CassandraConfig config) {
        CqlSessionBuilder builder = CqlSession.builder();

        // Add contact points
        for (String contactPoint : config.getContactPoints()) {
            String[] parts = contactPoint.split(":");
            String host = parts[0];
            int port = parts.length > 1 ? Integer.parseInt(parts[1]) : 9042;
            builder.addContactPoint(new InetSocketAddress(host, port));
        }

        builder.withLocalDatacenter(config.getLocalDatacenter());

        // Optional authentication
        if (config.hasAuthentication()) {
            builder.withAuthCredentials(config.getUsername(), config.getPassword());
        }

        // Optional SSL
        if (config.isSslEnabled()) {
            builder.withSslContext(config.getSslContext());
        }

        // Connection pool configuration
        builder.withConfigLoader(
            DriverConfigLoader.fromMap(config.getDriverConfig())
        );

        CqlSession session = builder.build();

        return new CassandraConnection(session, config);
    }

    public void ensureSchema(VectorIndexConfig indexConfig) {
        // Create keyspace if not exists
        session.execute(String.format("""
            CREATE KEYSPACE IF NOT EXISTS %s
            WITH replication = {'class': 'SimpleStrategy', 'replication_factor': %d}
            """,
            config.getKeyspace(),
            config.getReplicationFactor()
        ));

        // Use keyspace
        session.execute("USE " + config.getKeyspace());

        // Create table if not exists
        session.execute(String.format("""
            CREATE TABLE IF NOT EXISTS vectors (
                id int PRIMARY KEY,
                vector vector<float, %d>
            )
            """, indexConfig.getDimension()));

        // Create index if not exists
        session.execute(String.format("""
            CREATE CUSTOM INDEX IF NOT EXISTS vectors_ann_idx
            ON vectors(vector)
            USING 'StorageAttachedIndex'
            WITH OPTIONS = {
                'similarity_function': '%s',
                'maximum_node_connections': %d,
                'construction_beam_width': %d,
                'source_model': '%s'
            }
            """,
            indexConfig.getSimilarityFunction(),
            indexConfig.getMaxNodeConnections(),
            indexConfig.getConstructionBeamWidth(),
            indexConfig.getSourceModel()
        ));

        System.out.println("Schema ready in keyspace: " + config.getKeyspace());
    }

    public void loadDataset(DataSet ds, LoadingConfig loadConfig) {
        System.out.printf("Loading dataset %s (%d vectors)...%n",
            ds.name, ds.baseVectors.size());

        PreparedStatement insert = session.prepare(
            "INSERT INTO vectors (id, vector) VALUES (?, ?)"
        );

        int batchSize = loadConfig.getBatchSize();
        int concurrency = loadConfig.getConcurrency();

        // Use semaphore to limit concurrent requests
        Semaphore semaphore = new Semaphore(concurrency);
        List<CompletableFuture<Void>> futures = new ArrayList<>();

        long startTime = System.nanoTime();

        for (int i = 0; i < ds.baseVectors.size(); i += batchSize) {
            BatchStatementBuilder batch = BatchStatement.builder(
                loadConfig.isLogged() ? LOGGED : UNLOGGED
            );

            // Build batch
            for (int j = 0; j < batchSize && (i + j) < ds.baseVectors.size(); j++) {
                int id = i + j;
                VectorFloat<?> vector = ds.baseVectors.getVector(id);
                batch.addStatement(
                    insert.bind(id, vectorToList(vector))
                );
            }

            // Execute batch asynchronously with rate limiting
            int batchNum = i;
            semaphore.acquire();
            CompletableFuture<Void> future = session.executeAsync(batch.build())
                .toCompletableFuture()
                .thenAccept(rs -> {
                    if (batchNum % 10000 == 0) {
                        System.out.printf("Loaded %d vectors...%n", batchNum);
                    }
                    semaphore.release();
                });

            futures.add(future);
        }

        // Wait for all batches to complete
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        double elapsed = (System.nanoTime() - startTime) / 1e9;
        System.out.printf("Dataset loaded in %.2f seconds (%.0f vectors/sec)%n",
            elapsed, ds.baseVectors.size() / elapsed);

        // Wait for index to be built
        waitForIndexBuild();
    }

    public void waitForIndexBuild() {
        System.out.println("Waiting for index build to complete...");

        // Query system tables to check index build status
        // This is Cassandra-specific and may need adjustment
        while (true) {
            ResultSet rs = session.execute(
                "SELECT index_name FROM system_schema.indexes " +
                "WHERE keyspace_name = ? AND table_name = ?",
                config.getKeyspace(), "vectors"
            );

            if (rs.one() != null) {
                // Index exists, now check if it's ready
                // May need to query SAI-specific system tables
                break;
            }

            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted while waiting for index", e);
            }
        }

        System.out.println("Index build complete");
    }

    public SearchResult search(VectorFloat<?> query, int topK, SearchConfig searchConfig) {
        String cql = String.format("""
            SELECT id, vector, similarity_%s(vector, ?) as score
            FROM vectors
            ORDER BY vector ANN OF ?
            LIMIT %d
            """,
            searchConfig.getSimilarityFunction().toLowerCase(),
            topK
        );

        List<Float> queryList = vectorToList(query);

        ResultSet rs = session.execute(
            session.prepare(cql)
                .bind(queryList, queryList)
                .setConsistencyLevel(searchConfig.getReadConsistency())
        );

        // Convert Cassandra results to SearchResult format
        return convertToSearchResult(rs, topK);
    }

    private List<Float> vectorToList(VectorFloat<?> vector) {
        List<Float> list = new ArrayList<>(vector.length());
        for (int i = 0; i < vector.length(); i++) {
            list.add(vector.get(i));
        }
        return list;
    }

    private SearchResult convertToSearchResult(ResultSet rs, int topK) {
        // Convert ResultSet to jvector SearchResult format
        // This allows us to reuse existing AccuracyMetrics
        List<SearchResult.NodeScore> nodes = new ArrayList<>();

        for (Row row : rs) {
            int id = row.getInt("id");
            float score = row.getFloat("score");
            nodes.add(new SearchResult.NodeScore(id, score));
        }

        return new SearchResult(nodes, topK);
    }

    @Override
    public void close() {
        if (session != null) {
            session.close();
        }
    }
}

---

## Benchmark Implementations

### 1. CassandraThroughputBenchmark

**Goal:** Measure query throughput (QPS) through Cassandra

**Key Considerations:**
- Use connection pooling (matches parallel execution in jvector-bench)
- Account for CQL protocol overhead
- Network latency and cluster coordination overhead
- Cassandra driver async capabilities

```java
public class CassandraThroughputBenchmark implements CassandraBenchmark {
    private static volatile long SINK;

    @Override
    public List<Metric> runBenchmark(
            CassandraConnection connection,
            DataSet ds,
            int topK,
            SearchConfig searchConfig,
            int queryRuns) {

        int totalQueries = ds.queryVectors.size();

        // Warmup phase
        System.out.println("Warming up...");
        for (int warmup = 0; warmup < 3; warmup++) {
            IntStream.range(0, totalQueries)
                .parallel()
                .forEach(i -> {
                    VectorFloat<?> query = ds.queryVectors.get(i);
                    SearchResult sr = connection.search(query, topK, searchConfig);
                    SINK += sr.getNodes().size();
                });
        }

        // Test phase
        System.out.println("Running benchmark...");
        double[] qpsSamples = new double[queryRuns];
        for (int run = 0; run < queryRuns; run++) {
            System.gc();
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            long start = System.nanoTime();
            LongAdder counter = new LongAdder();

            IntStream.range(0, totalQueries)
                .parallel()
                .forEach(i -> {
                    VectorFloat<?> query = ds.queryVectors.get(i);
                    SearchResult sr = connection.search(query, topK, searchConfig);
                    counter.add(sr.getNodes().size());
                });

            double elapsed = (System.nanoTime() - start) / 1e9;
            qpsSamples[run] = totalQueries / elapsed;

            System.out.printf("Run %d: %.1f QPS%n", run, qpsSamples[run]);
            SINK += counter.sum();
        }

        // Calculate statistics
        double avgQps = StatUtils.mean(qpsSamples);
        double stdDev = Math.sqrt(StatUtils.variance(qpsSamples));
        double cv = (stdDev / avgQps) * 100;

        return List.of(
            Metric.of("Avg QPS (of " + queryRuns + ")", ".1f", avgQps),
            Metric.of("± Std Dev", ".1f", stdDev),
            Metric.of("CV %", ".1f", cv)
        );
    }
}
```

### 2. CassandraLatencyBenchmark

**Goal:** Measure per-query latency including all Cassandra layers

**Measured layers:**
- Network round-trip time
- CQL parsing and validation
- Coordinator node processing
- Storage layer access
- Vector index search
- Result assembly and serialization

```java
public class CassandraLatencyBenchmark implements CassandraBenchmark {
    private static volatile long SINK;

    @Override
    public List<Metric> runBenchmark(
            CassandraConnection connection,
            DataSet ds,
            int topK,
            SearchConfig searchConfig,
            int queryRuns) {

        int totalQueries = ds.queryVectors.size();
        List<Long> latencies = new ArrayList<>(totalQueries * queryRuns);

        System.out.println("Measuring latencies...");

        double mean = 0.0;
        double m2 = 0.0;
        int count = 0;

        for (int run = 0; run < queryRuns; run++) {
            for (int i = 0; i < totalQueries; i++) {
                VectorFloat<?> query = ds.queryVectors.get(i);

                long start = System.nanoTime();
                SearchResult sr = connection.search(query, topK, searchConfig);
                long duration = System.nanoTime() - start;

                latencies.add(duration);
                SINK += sr.getNodes().size();

                // Welford's online algorithm for variance
                count++;
                double delta = duration - mean;
                mean += delta / count;
                m2 += delta * (duration - mean);
            }
        }

        // Calculate statistics
        mean /= 1e6; // convert to milliseconds
        double stdDev = (count > 0) ? Math.sqrt(m2 / count) / 1e6 : 0.0;

        Collections.sort(latencies);
        int p50Index = (int) Math.ceil(0.50 * latencies.size()) - 1;
        int p95Index = (int) Math.ceil(0.95 * latencies.size()) - 1;
        int p99Index = (int) Math.ceil(0.99 * latencies.size()) - 1;
        int p999Index = (int) Math.ceil(0.999 * latencies.size()) - 1;

        double p50 = latencies.get(Math.max(0, p50Index)) / 1e6;
        double p95 = latencies.get(Math.max(0, p95Index)) / 1e6;
        double p99 = latencies.get(Math.max(0, p99Index)) / 1e6;
        double p999 = latencies.get(Math.max(0, p999Index)) / 1e6;

        return List.of(
            Metric.of("Mean Latency (ms)", ".3f", mean),
            Metric.of("STD Latency (ms)", ".3f", stdDev),
            Metric.of("p50 Latency (ms)", ".3f", p50),
            Metric.of("p95 Latency (ms)", ".3f", p95),
            Metric.of("p99 Latency (ms)", ".3f", p99),
            Metric.of("p999 Latency (ms)", ".3f", p999)
        );
    }
}
```

### 3. CassandraAccuracyBenchmark

**Goal:** Verify that Cassandra returns same results as direct jvector

**Critical validation:** Recall@K should be identical between jvector-direct and Cassandra tests

```java
public class CassandraAccuracyBenchmark implements CassandraBenchmark {

    @Override
    public List<Metric> runBenchmark(
            CassandraConnection connection,
            DataSet ds,
            int topK,
            SearchConfig searchConfig,
            int queryRuns) {

        int totalQueries = ds.queryVectors.size();

        System.out.println("Executing accuracy test...");

        // Execute all queries in parallel
        List<SearchResult> results = IntStream.range(0, totalQueries)
            .parallel()
            .mapToObj(i -> {
                VectorFloat<?> query = ds.queryVectors.get(i);
                return connection.search(query, topK, searchConfig);
            })
            .collect(Collectors.toList());

        // Compare against ground truth
        double recall = AccuracyMetrics.recallFromSearchResults(
            ds.groundTruth, results, topK, topK
        );

        double map = AccuracyMetrics.meanAveragePrecisionAtK(
            ds.groundTruth, results, topK
        );

        return List.of(
            Metric.of("Recall@" + topK, ".4f", recall),
            Metric.of("MAP@" + topK, ".4f", map)
        );
    }
}
```

### 4. CassandraInsertBenchmark (New)

**Goal:** Measure insert throughput and index build time

```java
public class CassandraInsertBenchmark implements CassandraBenchmark {

    public List<Metric> measureInsertPerformance(
            CassandraTestHarness harness,
            DataSet ds) {

        PreparedStatement insert = harness.getSession().prepare(
            "INSERT INTO bench.vectors (id, vector) VALUES (?, ?)"
        );

        long start = System.nanoTime();

        // Measure throughput of inserts
        int batchSize = 1000;
        for (int i = 0; i < ds.baseVectors.size(); i += batchSize) {
            BatchStatementBuilder batch = BatchStatement.builder(UNLOGGED);

            for (int j = 0; j < batchSize && (i + j) < ds.baseVectors.size(); j++) {
                VectorFloat<?> vector = ds.baseVectors.getVector(i + j);
                batch.addStatement(insert.bind(i + j, toFloatList(vector)));
            }

            harness.getSession().execute(batch.build());
        }

        double insertTime = (System.nanoTime() - start) / 1e9;

        // Wait for index to be built
        long indexStart = System.nanoTime();
        harness.waitForIndexBuild();
        double indexBuildTime = (System.nanoTime() - indexStart) / 1e9;

        double totalTime = insertTime + indexBuildTime;

        return List.of(
            Metric.of("Insert Time (s)", ".2f", insertTime),
            Metric.of("Index Build Time (s)", ".2f", indexBuildTime),
            Metric.of("Total Time (s)", ".2f", totalTime)
        );
    }
}
```

---

## Configuration Mapping

### JVector Direct Config → Cassandra CQL Options

| JVector Parameter | Cassandra CQL Option | Notes |
|-------------------|---------------------|--------|
| M (maxDegree) | maximum_node_connections | Direct mapping (default: 16) |
| efConstruction | construction_beam_width | Direct mapping (default: 100) |
| neighborOverflow | N/A | Not exposed in CQL (uses default 1.0) |
| addHierarchy | enable_hierarchy | Requires jvector v4+ |
| similarityFunction | similarity_function | DOT_PRODUCT, COSINE, EUCLIDEAN |
| ProductQuantization | source_model | Auto-configured per model |
| dimension | vector<float, N> | Set in table schema |

### Test Matrix

To ensure apples-to-apples comparison:

```yaml
configurations:
  - M: [8, 16, 32]
    efConstruction: [50, 100, 200]
    similarity: [DOT_PRODUCT, COSINE]
    source_model: [ADA002, OPENAI_V3_SMALL, OTHER]

search_parameters:
  - topK: [10, 100]
    rerankK: [20, 200]  # Maps to overquery in jvector-bench

datasets:
  - cap-1M
  - cohere-english-v3-1M
```

---

## Comparison Analysis

### Metrics to Compare

Create comparison reports showing:

```
Dataset: cap-1M, M=16, efConstruction=100, topK=10

                        JVector Direct    Cassandra    Overhead
────────────────────────────────────────────────────────────────
Index Build Time       123.4s            145.8s       +18.2%
Avg QPS                8,432             6,215        -26.3%
Mean Latency           2.34ms            3.89ms       +66.2%
p999 Latency           5.67ms            12.34ms      +117.6%
Recall@10              0.942             0.942        0.0%
```

### Expected Overhead Sources

**Cassandra overhead breakdown:**
1. **CQL Parsing & Validation** - ~0.1-0.5ms per query
2. **ByteBuffer Conversions** - ~0.05-0.1ms
3. **Storage Layer Access** - Variable (memtable vs SSTable)
4. **Row Assembly** - ~0.1-0.3ms
5. **Network (if not embedded)** - ~0.5-2ms

**Total expected overhead:** 15-50% latency increase, 20-40% QPS decrease

### Important: Accuracy Should Match

**Critical validation:** `Recall@K` should be **identical** between jvector-direct and Cassandra tests (within floating-point error).

If recall differs:
- Check that same index parameters are used
- Verify compression settings match
- Check for Cassandra-specific filtering affecting results

---

## Implementation Plan

### Phase 1: Foundation (Week 1)

1. **Add DataStax Java Driver dependency**
   ```xml
   <dependency>
       <groupId>com.datastax.oss</groupId>
       <artifactId>java-driver-core</artifactId>
       <version>4.17.0</version>
   </dependency>
   <dependency>
       <groupId>com.datastax.oss</groupId>
       <artifactId>java-driver-query-builder</artifactId>
       <version>4.17.0</version>
   </dependency>
   ```

2. **Create connection and configuration classes**
   - `CassandraConfig.java` - Parse connection config from YAML/CLI
   - `CassandraConnection.java` - Manage CQL session and schema
   - `VectorIndexConfig.java` - Vector index parameters
   - `SearchConfig.java` - Search parameters (topK, consistency, etc.)
   - `LoadingConfig.java` - Data loading parameters

3. **Create benchmark interface**
   ```java
   public interface CassandraBenchmark {
       String getBenchmarkName();
       List<Metric> runBenchmark(
           CassandraConnection connection,
           DataSet ds,
           int topK,
           SearchConfig searchConfig,
           int queryRuns
       );
   }
   ```

4. **Implement `CassandraBenchmarkRunner`** (analogous to `JvectorBench`)
   - Command-line argument parsing (--host, --datacenter, --keyspace, etc.)
   - Connection configuration loading
   - Connect to external cluster
   - Schema setup (if needed)
   - Dataset loading (separate operation)
   - Result collection and CSV/JSON output
   - Checkpoint support (reuse existing CheckpointManager)

**Key difference from JvectorBench:** Separates "setup" (loading data) from "benchmark" (running tests)

### Phase 2: Core Benchmarks (Week 2)

1. Implement `CassandraThroughputBenchmark`
2. Implement `CassandraLatencyBenchmark`
3. Implement `CassandraAccuracyBenchmark`
4. Implement `CassandraInsertBenchmark`

### Phase 3: Comparison Tools (Week 3)

1. Create comparison report generator
   - Side-by-side metrics
   - Overhead calculations
   - Visualization (charts/graphs)

2. Create regression detector
   - Compare current run to baseline
   - Flag significant deviations
   - CI/CD integration

### Phase 4: Advanced Testing (Week 4)

1. Add compaction impact tests
   - Measure performance after compaction
   - Compare with pre-compaction baseline

2. Add mixed workload tests
   - Concurrent inserts + queries
   - Update/delete operations

3. Add scale tests
   - Larger datasets (10M+)
   - Multiple concurrent clients

---

## Workflow: External Cluster Approach

### 1. Deploy Cassandra Cluster (User manages independently)

```bash
# Option 1: Docker (for local testing)
docker run -d --name cassandra \
  -p 9042:9042 \
  cassandra:latest

# Option 2: CCM (Cassandra Cluster Manager - for local multi-node)
ccm create test_cluster -v 5.0 -n 3
ccm start

# Option 3: Production cluster (user-managed)
# Use your existing cluster management tools
```

### 2. Prepare Dataset and Configuration

Create connection config (`cassandra-connection.yml`):
```yaml
cassandra:
  contact_points: ["127.0.0.1:9042"]
  local_datacenter: "datacenter1"
  keyspace: "jvector_bench"
  replication_factor: 1
```

### 3. Load Dataset (One-time Setup)

```bash
# Load dataset into Cassandra
java -jar cassandra-bench.jar load \
  --connection cassandra-connection.yml \
  --dataset cap-1M \
  --index-config vector-index-config.yml

# This will:
# 1. Connect to cluster
# 2. Create keyspace and table
# 3. Create vector index with specified parameters
# 4. Load all vectors from dataset
# 5. Wait for index build to complete
```

### 4. Run Benchmarks (Can run multiple times)

```bash
# Run benchmarks against loaded data
java -jar cassandra-bench.jar benchmark \
  --connection cassandra-connection.yml \
  --dataset cap-1M \
  --config bench-config.yml \
  --output results/cassandra-cap-1M

# This will:
# 1. Connect to cluster
# 2. Run throughput, latency, accuracy benchmarks
# 3. Collect metrics
# 4. Generate CSV and JSON output
```

### 5. Compare with JVector Direct

```bash
# Run jvector-direct benchmark (existing tool)
java -jar jvector-bench.jar \
  --output results/jvector-cap-1M \
  --config bench-config.yml \
  cap-1M

# Generate comparison report
java -jar cassandra-bench.jar compare \
  --jvector results/jvector-cap-1M.json \
  --cassandra results/cassandra-cap-1M.json \
  --output results/comparison-report.html
```

---

## File Structure

```
jvector-bench/
├── src/main/java/io/github/datastax/jvector/bench/
│   ├── cassandra/
│   │   ├── CassandraBenchmarkRunner.java (main entry point)
│   │   ├── CassandraConnection.java
│   │   ├── CassandraDataLoader.java
│   │   ├── benchmarks/
│   │   │   ├── CassandraBenchmark.java (interface)
│   │   │   ├── CassandraThroughputBenchmark.java
│   │   │   ├── CassandraLatencyBenchmark.java
│   │   │   ├── CassandraAccuracyBenchmark.java
│   │   │   └── CassandraInsertBenchmark.java
│   │   ├── config/
│   │   │   ├── CassandraConfig.java
│   │   │   ├── VectorIndexConfig.java
│   │   │   ├── SearchConfig.java
│   │   │   └── LoadingConfig.java
│   │   └── comparison/
│   │       ├── ComparisonReport.java
│   │       └── OverheadAnalyzer.java
│   └── ... (existing files)
├── src/main/resources/
│   ├── cassandra-configs/
│   │   ├── connection-local.yml (example)
│   │   ├── connection-cluster.yml (example)
│   │   └── vector-index-defaults.yml
│   └── yaml-configs/
│       └── ... (existing benchmark configs)
├── BENCHMARK_USAGE.md (existing - jvector-direct)
└── CASSANDRA_BENCHMARK_USAGE.md (new - Cassandra benchmarks)
```

---

## Testing Strategy

### Unit Tests

- Test CassandraTestHarness lifecycle
- Test schema creation with various configurations
- Test dataset loading
- Test query execution

### Integration Tests

- Test full benchmark runs on small datasets
- Test comparison report generation
- Test checkpoint/resume functionality

### End-to-End Tests

- Run full benchmark suite on standard datasets
- Compare results against known baselines
- Verify accuracy matches jvector-direct

---

## Open Questions

1. **Should we test through DataStax Java Driver or embed Cassandra?**
   - Recommendation: Start with embedded for isolation, add driver-based tests later

2. **How to handle rerankK parameter?**
   - Cassandra doesn't expose this directly in CQL
   - May need to modify Cassandra code to expose it OR
   - Test with default reranking behavior and document differences

3. **Should we test single-node or multi-node?**
   - Recommendation: Start single-node, expand to multi-node later

4. **How to measure just jvector overhead vs total Cassandra overhead?**
   - Add instrumentation to Cassandra code to measure jvector-specific timing
   - Compare against baseline queries without vector index

---

## Success Criteria

1. **Functional Equivalence**
   - Accuracy metrics match jvector-direct (within 0.1%)

2. **Reproducible Results**
   - Results stable across runs (CV < 5%)

3. **Comprehensive Coverage**
   - All jvector-bench scenarios covered
   - All Cassandra-specific scenarios added

4. **Clear Comparison**
   - Easy to understand overhead breakdown
   - Identifies performance bottlenecks

5. **CI/CD Integration**
   - Automated regression detection
   - Performance tracking over time

---

## Next Steps

1. Review this plan with the team
2. Set up development environment with Cassandra
3. Implement Phase 1 (Foundation)
4. Run initial benchmarks and validate approach
5. Iterate based on findings
