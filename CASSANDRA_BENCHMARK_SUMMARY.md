# Cassandra Benchmark Implementation Summary

## Overview

This project extends jvector-bench to support benchmarking jvector performance when accessed through Cassandra, enabling apples-to-apples comparison between:
- **jvector-direct**: Testing jvector library directly via Java API
- **jvector-via-cassandra**: Testing jvector through Cassandra's full stack (CQL, storage, networking)

## Architecture Decision: External Cluster

**Key Design:** The benchmark framework **connects to an independently deployed Cassandra cluster** rather than embedding Cassandra.

### Why External Cluster?

| Aspect | External Cluster ✅ | Embedded Cassandra ❌ |
|--------|-------------------|---------------------|
| **Separation of Concerns** | Cluster management independent | Tightly coupled |
| **Realistic Testing** | Tests full stack + network | Skips network layer |
| **Flexibility** | Any cluster config | Limited to embedded config |
| **Repeatability** | Load once, test many times | Rebuild for each test |
| **Production Validation** | Can test real clusters | Cannot test prod |
| **Network Overhead** | Measured accurately | Not measured |

### User Experience

**Your responsibility:**
1. Deploy/manage Cassandra cluster (Docker, CCM, k8s, bare metal, etc.)
2. Ensure cluster is running and accessible
3. Provide connection details (host, datacenter, credentials)

**Framework's responsibility:**
1. Connect to your cluster
2. Set up schema (keyspace, table, index)
3. Load dataset
4. Run benchmarks
5. Collect metrics
6. Generate comparison reports

## Workflow Comparison

### JVector-Direct (Existing)

```bash
# One command does everything
java -jar jvector-bench.jar \
  --output results/cap-1M \
  cap-1M

# Output: CSV + JSON with QPS, latency, recall
```

### JVector-via-Cassandra (New)

```bash
# Step 1: Deploy Cassandra (you manage this)
docker run -d --name cassandra -p 9042:9042 cassandra:5.0

# Step 2: Load dataset (one-time setup)
java -jar cassandra-bench.jar load \
  --connection cassandra-connection.yml \
  --dataset cap-1M \
  --index-config vector-index.yml

# Step 3: Run benchmarks (can repeat many times)
java -jar cassandra-bench.jar benchmark \
  --connection cassandra-connection.yml \
  --dataset cap-1M \
  --output results/cassandra-cap-1M

# Step 4: Compare results
java -jar cassandra-bench.jar compare \
  --jvector results/jvec-cap-1M.json \
  --cassandra results/cassandra-cap-1M.json \
  --output comparison-report.html
```

## Components to Implement

### Core Classes

1. **CassandraConnection.java**
   - Manages CQL session lifecycle
   - Handles connection pooling
   - Executes queries
   - Converts results to jvector format

2. **CassandraConfig.java**
   - Parses connection config YAML
   - Handles authentication, SSL
   - Configures driver settings

3. **CassandraDataLoader.java**
   - Loads datasets into Cassandra
   - Creates schema (keyspace, table, index)
   - Batch inserts with rate limiting
   - Waits for index build completion

4. **CassandraBenchmarkRunner.java**
   - Main entry point (like JvectorBench)
   - Parses command-line arguments
   - Orchestrates load/benchmark/compare operations
   - Generates output files

### Benchmark Classes

1. **CassandraThroughputBenchmark.java**
   - Measures QPS with parallel queries
   - Includes warmup phase
   - Calculates mean, stddev, CV%

2. **CassandraLatencyBenchmark.java**
   - Measures per-query latency
   - Calculates mean, stddev, p50, p95, p99, p999
   - Uses Welford's algorithm for variance

3. **CassandraAccuracyBenchmark.java**
   - Compares results against ground truth
   - Calculates Recall@K and MAP@K
   - **Critical:** Should match jvector-direct exactly

4. **CassandraInsertBenchmark.java** (new)
   - Measures insert throughput
   - Measures index build time
   - Cassandra-specific (no equivalent in jvector-direct)

### Comparison Tools

1. **ComparisonReport.java**
   - Side-by-side metrics comparison
   - Overhead calculations
   - HTML/JSON/Markdown output

2. **OverheadAnalyzer.java**
   - Breaks down overhead sources
   - Flags significant differences
   - Provides optimization recommendations

## Configuration Files

### cassandra-connection.yml

```yaml
cassandra:
  contact_points: ["127.0.0.1:9042"]
  local_datacenter: "datacenter1"
  keyspace: "jvector_bench"
  replication_factor: 1
  username: "cassandra"  # optional
  password: "cassandra"  # optional
  write_consistency: "ONE"
  read_consistency: "ONE"
```

### vector-index-config.yml

```yaml
vector_index:
  dimension: 1536
  similarity_function: "DOT_PRODUCT"
  maximum_node_connections: 16
  construction_beam_width: 100
  source_model: "OPENAI_V3_LARGE"
```

## Expected Results

### Performance Overhead

Compared to jvector-direct:

| Metric | Expected Cassandra Overhead |
|--------|---------------------------|
| **QPS** | -20% to -40% slower |
| **Mean Latency** | +15% to +50% higher |
| **p99 Latency** | +50% to +150% higher |
| **Recall@K** | 0% (must match exactly) |
| **Index Build** | +10% to +30% longer |

### Overhead Sources

1. **Network RTT** (~0.5-2ms for local, more for remote)
2. **CQL Parsing** (~0.1-0.5ms)
3. **Coordinator Processing** (~0.1-0.3ms)
4. **Storage Layer Access** (variable, memtable vs SSTable)
5. **Result Serialization** (~0.1-0.2ms)
6. **Driver Overhead** (~0.05-0.1ms)

**Total:** ~1-4ms baseline + jvector search time

### Critical Validation

**Recall@K MUST be identical** between jvector-direct and Cassandra.

If recall differs, something is wrong:
- Index parameters don't match
- Compression settings differ
- Dataset versions differ
- Bug in Cassandra integration

## Implementation Phases

### Phase 1: Foundation (Week 1)
- Add DataStax Java Driver dependency
- Implement CassandraConfig, CassandraConnection
- Implement CassandraDataLoader
- Create benchmark interface
- Implement CassandraBenchmarkRunner main class

### Phase 2: Core Benchmarks (Week 2)
- Implement CassandraThroughputBenchmark
- Implement CassandraLatencyBenchmark
- Implement CassandraAccuracyBenchmark
- Implement CassandraInsertBenchmark

### Phase 3: Comparison Tools (Week 3)
- Implement ComparisonReport generator
- Implement OverheadAnalyzer
- Create HTML/JSON/Markdown formatters

### Phase 4: Validation (Week 4)
- Test with multiple datasets
- Validate accuracy matches jvector-direct
- Performance tuning and optimization
- Documentation and examples

## Testing Strategy

### Unit Tests
- Connection management
- Configuration parsing
- Result conversion
- Metric calculations

### Integration Tests
- Connect to test cluster
- Schema creation
- Data loading
- Query execution
- Result validation

### End-to-End Tests
- Full workflow: load → benchmark → compare
- Multiple datasets
- Various configurations
- Regression detection

## Success Criteria

1. **Functional Equivalence**
   - ✅ Accuracy (Recall@K) matches jvector-direct within 0.01%

2. **Performance Measurement**
   - ✅ Overhead measured accurately
   - ✅ Results reproducible (CV < 5%)

3. **Usability**
   - ✅ Easy to connect to any Cassandra cluster
   - ✅ Clear configuration
   - ✅ Helpful error messages

4. **Completeness**
   - ✅ All jvector-bench scenarios covered
   - ✅ Cassandra-specific scenarios added
   - ✅ Comparison reports generated

5. **Documentation**
   - ✅ Clear usage guide
   - ✅ Configuration examples
   - ✅ Troubleshooting guide

## Benefits of This Approach

1. **Realistic Performance Data**
   - Measures actual production overhead
   - Includes network and coordination costs
   - Reflects real-world usage patterns

2. **Flexible Testing**
   - Test any cluster configuration
   - Test with different hardware
   - Test with various load patterns
   - Test production clusters safely (read-only benchmarks)

3. **Separation of Concerns**
   - Cluster management is independent
   - Framework focuses on benchmarking
   - No embedded dependencies
   - Easier to maintain

4. **Repeatable Experiments**
   - Load dataset once
   - Run benchmarks many times
   - Compare across configurations
   - Track performance over time

5. **CI/CD Integration**
   - Can run against ephemeral clusters
   - Automated regression detection
   - Performance tracking over commits
   - Release validation

## Next Steps

1. **Review this plan** - Ensure it meets your requirements
2. **Set up test Cassandra cluster** - Docker or CCM for development
3. **Implement Phase 1** - Connection and data loading
4. **Validate with small dataset** - siftsmall or similar
5. **Implement benchmarks** - Phases 2-3
6. **Full validation** - Phase 4
7. **Production testing** - Real workloads

## Questions to Consider

1. **Should we support multiple tables?** (e.g., one per dataset)
2. **Should we support custom CQL queries?** (for advanced users)
3. **Should we measure coordinator vs replica performance separately?**
4. **Should we support tracing/metrics collection from Cassandra?**
5. **Should we support mixed read/write workloads?**

These can be addressed in later iterations.

## Resources

- **CASSANDRA_BENCHMARK_PLAN.md** - Detailed implementation plan with code examples
- **CASSANDRA_BENCHMARK_USAGE.md** - Complete usage guide for end users
- **Cassandra codebase analysis** - Located at /tmp/cassandra_jvector_integration_summary.md

## Contact

For questions or clarifications, please reach out or file an issue.
