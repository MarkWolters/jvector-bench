# Cassandra Benchmark Usage Guide

This guide covers how to benchmark jvector performance through Cassandra, using an externally deployed Cassandra cluster.

---

## Prerequisites

1. **Cassandra Cluster** - A running Cassandra cluster (single-node or multi-node)
   - Version 5.0+ (with SAI vector search support)
   - Accessible via CQL (port 9042 by default)
   - jvector library integrated (DataStax Enterprise or open-source with patches)

2. **Connection Details** - Know your cluster's:
   - Contact points (IP addresses/hostnames)
   - Local datacenter name
   - Credentials (if authentication enabled)

3. **Dataset Files** - HDF5 files available (same as jvector-bench)
   - Located in expected directory or configured path

---

## Quick Start

### 1. Deploy Cassandra (One-time Setup)

**Option A: Docker (for local testing)**
```bash
docker run -d --name cassandra \
  -p 9042:9042 \
  -e CASSANDRA_DC=datacenter1 \
  cassandra:5.0

# Wait for Cassandra to be ready
docker exec cassandra nodetool status
```

**Option B: CCM (for multi-node local testing)**
```bash
ccm create bench_cluster -v 5.0 -n 3
ccm start
ccm status
```

**Option C: Use existing cluster**
```bash
# Just note your connection details
```

### 2. Create Connection Configuration

Create `cassandra-connection.yml`:
```yaml
cassandra:
  contact_points:
    - "127.0.0.1:9042"
  local_datacenter: "datacenter1"
  keyspace: "jvector_bench"
  replication_factor: 1

  # Optional: for authenticated clusters
  # username: "cassandra"
  # password: "cassandra"

  # Optional: connection tuning
  connection:
    max_requests_per_connection: 1024
    pool_local_size: 4

  # Optional: consistency levels
  write_consistency: "ONE"
  read_consistency: "ONE"
```

### 3. Load Dataset

```bash
# Load dataset into Cassandra and build vector index
java -jar cassandra-bench.jar load \
  --connection cassandra-connection.yml \
  --dataset cap-1M \
  --index-config default-vector-index.yml \
  --batch-size 500 \
  --concurrency 32

# This will:
# - Connect to your Cassandra cluster
# - Create keyspace "jvector_bench" if it doesn't exist
# - Create table "vectors" with vector column
# - Create SAI vector index with specified parameters
# - Load all 1M vectors from cap-1M dataset
# - Wait for index build to complete
# - Report loading time and throughput
```

Expected output:
```
Connected to cluster: Test Cluster [127.0.0.1:9042]
Creating keyspace: jvector_bench
Creating table: vectors
Creating vector index with config:
  - similarity_function: DOT_PRODUCT
  - maximum_node_connections: 16
  - construction_beam_width: 100
  - source_model: OPENAI_V3_LARGE

Loading dataset cap-1M (1000000 vectors)...
Loaded 100000 vectors...
Loaded 200000 vectors...
...
Dataset loaded in 125.3 seconds (7980 vectors/sec)

Waiting for index build to complete...
Index build complete (estimated 45.2 seconds)

Total time: 170.5 seconds
```

### 4. Run Benchmarks

```bash
# Run performance benchmarks
java -jar cassandra-bench.jar benchmark \
  --connection cassandra-connection.yml \
  --dataset cap-1M \
  --config bench-config.yml \
  --output results/cassandra-cap-1M

# This will:
# - Connect to cluster
# - Load query vectors from dataset
# - Run throughput benchmark (QPS)
# - Run latency benchmark (mean, p50, p95, p99, p999)
# - Run accuracy benchmark (Recall@10, MAP)
# - Generate CSV and JSON results
```

Expected output:
```
Connected to cluster: Test Cluster [127.0.0.1:9042]
Using keyspace: jvector_bench
Loading query vectors from dataset: cap-1M (10000 queries)

Running ThroughputBenchmark...
Warming up...
Run 0: 3245.2 QPS
Run 1: 3312.8 QPS
Run 2: 3298.5 QPS
Average: 3285.5 QPS ± 34.7

Running LatencyBenchmark...
Measuring latencies...
Mean: 3.04ms, p50: 2.89ms, p95: 5.12ms, p99: 7.34ms, p999: 12.45ms

Running AccuracyBenchmark...
Executing accuracy test...
Recall@10: 0.9423, MAP@10: 0.8876

Results written to:
  - results/cassandra-cap-1M.csv
  - results/cassandra-cap-1M.json
```

---

## Command Reference

### cassandra-bench load

**Purpose:** Load dataset into Cassandra and build vector index

**Usage:**
```bash
java -jar cassandra-bench.jar load \
  --connection <connection-config.yml> \
  --dataset <dataset-name> \
  --index-config <index-config.yml> \
  [OPTIONS]
```

**Required Arguments:**
- `--connection <path>` - Path to Cassandra connection config YAML
- `--dataset <name>` - Dataset name (e.g., cap-1M, cohere-english-v3-1M)
- `--index-config <path>` - Path to vector index configuration YAML

**Optional Arguments:**
- `--batch-size <n>` - Number of inserts per batch (default: 500)
- `--concurrency <n>` - Number of concurrent batch operations (default: 32)
- `--drop-existing` - Drop existing table/index if present (default: false)
- `--skip-index-wait` - Don't wait for index build to complete (default: false)

**Example:**
```bash
java -jar cassandra-bench.jar load \
  --connection cassandra-connection.yml \
  --dataset cohere-english-v3-1M \
  --index-config cohere-index.yml \
  --batch-size 1000 \
  --concurrency 64
```

---

### cassandra-bench benchmark

**Purpose:** Run performance benchmarks against loaded dataset

**Usage:**
```bash
java -jar cassandra-bench.jar benchmark \
  --connection <connection-config.yml> \
  --dataset <dataset-name> \
  --output <output-path> \
  [OPTIONS]
```

**Required Arguments:**
- `--connection <path>` - Path to Cassandra connection config YAML
- `--dataset <name>` - Dataset name (must match loaded dataset)
- `--output <path>` - Base path for output files (.csv, .json)

**Optional Arguments:**
- `--config <path>` - Path to benchmark configuration YAML
- `--topK <n>` - Number of results to return (default: 10)
- `--query-runs <n>` - Number of times to run full query set (default: 2)
- `--skip-throughput` - Skip throughput benchmark
- `--skip-latency` - Skip latency benchmark
- `--skip-accuracy` - Skip accuracy benchmark
- `--warmup-runs <n>` - Number of warmup runs (default: 3)

**Example:**
```bash
java -jar cassandra-bench.jar benchmark \
  --connection cassandra-connection.yml \
  --dataset cap-1M \
  --output results/cap-1M-run1 \
  --topK 100 \
  --query-runs 5
```

---

### cassandra-bench compare

**Purpose:** Compare jvector-direct results with Cassandra results

**Usage:**
```bash
java -jar cassandra-bench.jar compare \
  --jvector <jvector-results.json> \
  --cassandra <cassandra-results.json> \
  --output <report-path>
```

**Required Arguments:**
- `--jvector <path>` - Path to jvector-bench results JSON
- `--cassandra <path>` - Path to cassandra-bench results JSON
- `--output <path>` - Output path for comparison report

**Optional Arguments:**
- `--format <html|json|markdown>` - Report format (default: html)
- `--threshold <percent>` - Flag differences above threshold (default: 10)

**Example:**
```bash
# Run jvector-direct benchmark
java -jar jvector-bench.jar \
  --output results/jvector-cap-1M \
  cap-1M

# Run Cassandra benchmark
java -jar cassandra-bench.jar benchmark \
  --connection cassandra-connection.yml \
  --dataset cap-1M \
  --output results/cassandra-cap-1M

# Compare results
java -jar cassandra-bench.jar compare \
  --jvector results/jvector-cap-1M.json \
  --cassandra results/cassandra-cap-1M.json \
  --output results/comparison-report.html
```

---

## Configuration Files

### Connection Configuration

**File:** `cassandra-connection.yml`

**Full example:**
```yaml
cassandra:
  # Required: cluster connection details
  contact_points:
    - "192.168.1.10:9042"
    - "192.168.1.11:9042"
    - "192.168.1.12:9042"
  local_datacenter: "datacenter1"
  keyspace: "jvector_bench"

  # Optional: replication settings
  replication_factor: 3
  replication_strategy: "SimpleStrategy"
  # Or for multi-DC:
  # replication_strategy: "NetworkTopologyStrategy"
  # replication_config:
  #   datacenter1: 3
  #   datacenter2: 3

  # Optional: authentication
  username: "cassandra_user"
  password: "secure_password"

  # Optional: SSL/TLS
  ssl:
    enabled: true
    truststore_path: "/path/to/truststore.jks"
    truststore_password: "truststore_pass"

  # Optional: connection pool settings
  connection:
    max_requests_per_connection: 1024
    pool_local_size: 4
    pool_remote_size: 2
    connect_timeout_ms: 5000
    read_timeout_ms: 12000

  # Optional: consistency levels
  write_consistency: "LOCAL_QUORUM"
  read_consistency: "LOCAL_QUORUM"

  # Optional: retry policy
  retry_policy: "DEFAULT"  # DEFAULT, FALLTHROUGH, DOWNGRADING
```

### Vector Index Configuration

**File:** `vector-index-config.yml`

```yaml
vector_index:
  # Required: basic parameters
  dimension: 1536
  similarity_function: "DOT_PRODUCT"  # DOT_PRODUCT, COSINE, EUCLIDEAN

  # Optional: graph construction parameters
  maximum_node_connections: 16  # M parameter (default: 16)
  construction_beam_width: 100   # efConstruction (default: 100)

  # Optional: compression/model hint
  source_model: "OPENAI_V3_LARGE"
  # Options: ADA002, OPENAI_V3_SMALL, OPENAI_V3_LARGE, BERT,
  #          GECKO, COHERE_V3, NV_QA_4, OTHER

  # Optional: advanced parameters (if supported)
  enable_hierarchy: false
```

### Benchmark Configuration

**File:** `bench-config.yml`

This uses the same format as jvector-bench configs, with Cassandra-specific additions:

```yaml
dataset: "cap-1M"

# Search parameters
search:
  topK:
    10: [1.0, 2.0]
    100: [1.0, 2.0]
  # Note: overquery multiplier might not be exposed in Cassandra CQL
  # In that case, only base topK is used

# Cassandra-specific parameters
cassandra:
  # Consistency levels override (optional)
  read_consistency: "LOCAL_ONE"

  # Timeout overrides (optional)
  query_timeout_ms: 10000
```

---

## Output Files

### CSV Output

**File:** `<output>.csv`

Columns:
- `dataset` - Dataset name
- `QPS` - Queries per second (average)
- `QPS StdDev` - Standard deviation of QPS
- `Mean Latency` - Mean query latency (ms)
- `p50 Latency` - Median latency (ms)
- `p95 Latency` - 95th percentile latency (ms)
- `p99 Latency` - 99th percentile latency (ms)
- `p999 Latency` - 99.9th percentile latency (ms)
- `Recall@10` - Recall at K=10
- `MAP@10` - Mean Average Precision at K=10

**Example:**
```csv
dataset,QPS,QPS StdDev,Mean Latency,p50 Latency,p95 Latency,p99 Latency,p999 Latency,Recall@10,MAP@10
cap-1M,3285.5,34.7,3.04,2.89,5.12,7.34,12.45,0.9423,0.8876
```

### JSON Output

**File:** `<output>.json`

Detailed results for each benchmark run:

```json
[
  {
    "dataset": "cap-1M",
    "parameters": {
      "M": 16,
      "efConstruction": 100,
      "topK": 10,
      "similarity_function": "DOT_PRODUCT",
      "source_model": "OPENAI_V3_LARGE"
    },
    "metrics": {
      "Avg QPS (of 3)": 3285.5,
      "± Std Dev": 34.7,
      "CV %": 1.06
    },
    "timestamp": "2025-11-12T10:30:45Z",
    "cluster": "127.0.0.1:9042"
  },
  {
    "dataset": "cap-1M",
    "parameters": {...},
    "metrics": {
      "Mean Latency (ms)": 3.04,
      "STD Latency (ms)": 0.45,
      "p50 Latency (ms)": 2.89,
      "p95 Latency (ms)": 5.12,
      "p99 Latency (ms)": 7.34,
      "p999 Latency (ms)": 12.45
    },
    "timestamp": "2025-11-12T10:35:22Z"
  },
  ...
]
```

---

## Typical Workflows

### Workflow 1: Single Dataset Comparison

Compare jvector-direct vs Cassandra for one dataset:

```bash
# 1. Start Cassandra
docker run -d --name cassandra -p 9042:9042 cassandra:5.0

# 2. Load dataset
java -jar cassandra-bench.jar load \
  --connection conn.yml \
  --dataset cap-1M \
  --index-config default.yml

# 3. Run Cassandra benchmark
java -jar cassandra-bench.jar benchmark \
  --connection conn.yml \
  --dataset cap-1M \
  --output results/cass-cap-1M

# 4. Run jvector-direct benchmark
java -jar jvector-bench.jar \
  --output results/jvec-cap-1M \
  cap-1M

# 5. Compare
java -jar cassandra-bench.jar compare \
  --jvector results/jvec-cap-1M.json \
  --cassandra results/cass-cap-1M.json \
  --output comparison.html
```

### Workflow 2: Parameter Sweep

Test different index configurations:

```bash
# Load dataset with config 1 (M=8)
java -jar cassandra-bench.jar load \
  --connection conn.yml \
  --dataset cap-1M \
  --index-config config-m8.yml \
  --drop-existing

# Benchmark
java -jar cassandra-bench.jar benchmark \
  --connection conn.yml \
  --dataset cap-1M \
  --output results/cap-1M-m8

# Repeat with M=16
java -jar cassandra-bench.jar load \
  --connection conn.yml \
  --dataset cap-1M \
  --index-config config-m16.yml \
  --drop-existing

java -jar cassandra-bench.jar benchmark \
  --connection conn.yml \
  --dataset cap-1M \
  --output results/cap-1M-m16

# Repeat with M=32
# ... etc
```

### Workflow 3: Multi-Dataset Regression Testing

Test multiple datasets in CI/CD:

```bash
#!/bin/bash
datasets=("cap-1M" "cohere-english-v3-1M" "dpr-1M")

for dataset in "${datasets[@]}"; do
  echo "Testing $dataset..."

  # Load
  java -jar cassandra-bench.jar load \
    --connection conn.yml \
    --dataset "$dataset" \
    --index-config default.yml \
    --drop-existing

  # Benchmark
  java -jar cassandra-bench.jar benchmark \
    --connection conn.yml \
    --dataset "$dataset" \
    --output "results/cass-$dataset"

  # Compare with baseline
  java -jar cassandra-bench.jar compare \
    --jvector "baseline/jvec-$dataset.json" \
    --cassandra "results/cass-$dataset.json" \
    --output "results/comparison-$dataset.html"
done
```

---

## Troubleshooting

### Connection Issues

**Problem:** `Cannot connect to Cassandra cluster`

**Solutions:**
- Verify Cassandra is running: `docker ps` or `ccm status`
- Check contact points are correct
- Ensure port 9042 is accessible
- Verify datacenter name matches cluster configuration

### Index Build Timeout

**Problem:** `Timeout waiting for index build`

**Solutions:**
- Large datasets take time to index (be patient)
- Use `--skip-index-wait` and check manually later
- Check Cassandra logs for index build progress
- Ensure adequate resources (CPU, memory, disk I/O)

### Low Performance

**Problem:** QPS much lower than expected

**Possible causes:**
- Network latency (test with local cluster)
- Inadequate connection pool size (increase in config)
- Cassandra resource constraints (check `nodetool tpstats`)
- Disk I/O bottleneck (use SSD storage)
- JVM heap too small (increase with `-Xmx`)

### Recall Mismatch

**Problem:** Cassandra recall differs from jvector-direct

**Critical - investigate immediately:**
- Verify index parameters match exactly
- Check similarity function is identical
- Ensure dataset versions match
- Check for compression/quantization differences
- Review Cassandra logs for warnings

---

## Performance Expectations

Based on initial testing, expected overhead vs jvector-direct:

| Metric | Cassandra Overhead | Notes |
|--------|-------------------|-------|
| QPS | -20% to -40% | Network + CQL parsing + coordination |
| Mean Latency | +15% to +50% | Depends on network latency |
| p99 Latency | +50% to +150% | Tail latencies affected by GC, network variance |
| Recall@K | 0% (identical) | Should match exactly |
| Index Build Time | +10% to +30% | Background building, memtable overhead |

**Note:** These are rough estimates. Your results will vary based on:
- Network topology (local vs remote)
- Cluster size and configuration
- Hardware resources
- Data characteristics
- Concurrent load

---

## Next Steps

1. **Set up your Cassandra cluster** (or use existing one)
2. **Create connection configuration** with your cluster details
3. **Load a small dataset** (e.g., siftsmall) to verify everything works
4. **Run initial benchmarks** and compare with jvector-direct
5. **Scale up** to larger datasets and more complex configurations
6. **Analyze results** and identify performance bottlenecks
7. **Tune configuration** based on findings
8. **Automate** for regression testing

For questions or issues, please file an issue on GitHub.
