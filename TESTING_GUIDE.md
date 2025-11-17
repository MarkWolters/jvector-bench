# JVector Benchmark Testing Guide

This guide provides step-by-step instructions for running benchmarks and tests against both JVector directly and through Cassandra integration.

## Table of Contents

- [Prerequisites](#prerequisites)
- [JVector Direct Testing](#jvector-direct-testing)
- [Cassandra Integration Testing](#cassandra-integration-testing)
- [Comparing Results](#comparing-results)
- [Troubleshooting](#troubleshooting)

---

## Prerequisites

### Required Software

1. **Java 20+** installed and configured
   ```bash
   java -version  # Should show version 20.x.x
   javac -version # Should show version 20.x.x
   ```

2. **Maven** (wrapper included in project)
   ```bash
   ./mvnw --version
   ```

3. **Built jvector-bench JAR**
   ```bash
   ./mvnw clean package
   ```

### For Cassandra Testing

4. **Docker** (recommended) or **CCM** (Cassandra Cluster Manager)
   ```bash
   docker --version
   # OR
   ccm --version
   ```

5. **Cassandra 5.0+** with SAI vector search support

---

## JVector Direct Testing

Testing JVector library performance directly without Cassandra.

### 1. Quick Test with Small Dataset

Start with the small `siftsmall` dataset (10K vectors) to verify everything works:

```bash
java -jar target/jvector-bench-1.0.0-SNAPSHOT-jar-with-dependencies.jar jvector \
  --output results/jvector-siftsmall \
  siftsmall
```

**Expected output:**
```
Loading dataset: siftsmall
Running benchmarks...
Throughput: ~15000-25000 QPS
Latency: ~0.04-0.08ms mean
Recall@10: ~0.95-0.99
Results written to results/jvector-siftsmall.csv and .json
```

**Success criteria:**
- ✅ Benchmark completes without errors
- ✅ CSV and JSON files created
- ✅ Recall@10 > 0.90
- ✅ QPS > 10000

### 2. Test with Medium Dataset

Test with a 1M vector dataset:

```bash
java -Xmx8g -jar target/jvector-bench-1.0.0-SNAPSHOT-jar-with-dependencies.jar jvector \
  --output results/jvector-cap-1M \
  cap-1M
```

**Note:** Increase heap memory (`-Xmx8g`) for larger datasets.

**Expected output:**
```
Loading dataset: cap-1M (1000000 vectors, dimension 1536)
Building index...
Running benchmarks...
Throughput: ~5000-15000 QPS
Latency: ~0.1-0.3ms mean
Recall@10: ~0.92-0.96
Index construction time: ~30-60 seconds
```

### 3. Test Multiple Datasets

Run benchmarks on multiple datasets in one command:

```bash
java -Xmx16g -jar target/jvector-bench-1.0.0-SNAPSHOT-jar-with-dependencies.jar jvector \
  --output results/jvector-multi \
  cap-1M dpr-1M cohere-english-v3-1M
```

### 4. Test with Custom Configuration

Create a custom config file `test-config.yml`:

```yaml
dataset: cap-1M

construction:
  outDegree: [16, 32]
  efConstruction: [100, 200]
  neighborOverflow: [1.2]

search:
  topKOverquery:
    10: [40, 80]
    100: [100, 200]
```

Run with custom config:

```bash
java -jar target/jvector-bench-1.0.0-SNAPSHOT-jar-with-dependencies.jar jvector \
  --output results/jvector-custom \
  --config test-config.yml \
  cap-1M
```

### 5. Test with Diagnostics

Enable detailed diagnostics for debugging:

```bash
java -jar target/jvector-bench-1.0.0-SNAPSHOT-jar-with-dependencies.jar jvector \
  --output results/jvector-debug \
  --diag 2 \
  siftsmall
```

Diagnostic levels:
- `0`: No diagnostics (default)
- `1`: Basic diagnostics
- `2`: Detailed diagnostics
- `3`: Full diagnostics with verbose logging

### 6. Test Checkpoint/Resume

Test the checkpoint and resume functionality:

```bash
# Start benchmark with multiple datasets
java -jar target/jvector-bench-1.0.0-SNAPSHOT-jar-with-dependencies.jar jvector \
  --output results/checkpoint-test \
  cap-1M dpr-1M cohere-english-v3-1M

# Interrupt it (Ctrl+C) after first dataset completes

# Resume - it will skip completed datasets
java -jar target/jvector-bench-1.0.0-SNAPSHOT-jar-with-dependencies.jar jvector \
  --output results/checkpoint-test \
  cap-1M dpr-1M cohere-english-v3-1M
```

**Verify:**
- Check `results/checkpoint-test.checkpoint.json` exists
- Completed datasets are marked
- Resume skips completed datasets

---

## Cassandra Integration Testing

Testing JVector performance through Cassandra's vector search.

### 1. Deploy Cassandra

**Option A: Docker (Recommended for Testing)**

```bash
# Start Cassandra
docker run -d --name cassandra \
  -p 9042:9042 \
  -e CASSANDRA_DC=datacenter1 \
  cassandra:5.0

# Wait for Cassandra to be ready (takes 30-60 seconds)
sleep 60

# Verify it's running
docker exec cassandra nodetool status
```

**Expected output:**
```
Datacenter: datacenter1
Status=Up/Down
|/ State=Normal/Leaving/Joining/Moving
--  Address    Load       Tokens  Owns    Host ID                               Rack
UN  127.0.0.1  XXX KB     256     100.0%  xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx  rack1
```

**Option B: CCM (For Multi-Node Testing)**

```bash
# Create and start cluster
ccm create bench_cluster -v 5.0 -n 1
ccm start

# Check status
ccm status
```

### 2. Verify Cassandra Connection

Test connection using cqlsh:

```bash
docker exec -it cassandra cqlsh
```

In cqlsh:
```sql
DESCRIBE KEYSPACES;
-- Press Ctrl+D to exit
```

### 3. Prepare Configuration Files

The project includes example configs in `src/main/resources/cassandra-configs/`:

**Connection config** (`connection-local.yml`):
```yaml
cassandra:
  contact_points:
    - "127.0.0.1:9042"
  local_datacenter: "datacenter1"
  keyspace: "jvector_bench"
  replication_factor: 1
```

**Vector index config for siftsmall** (create `vector-index-siftsmall.yml`):
```yaml
vector_index:
  dimension: 128
  similarity_function: "EUCLIDEAN"
  maximum_node_connections: 16
  construction_beam_width: 100
  source_model: "OTHER"
```

### 4. Load Dataset into Cassandra

Start with the small `siftsmall` dataset:

```bash
java -jar target/jvector-bench-1.0.0-SNAPSHOT-jar-with-dependencies.jar cassandra load \
  --connection src/main/resources/cassandra-configs/connection-local.yml \
  --dataset siftsmall \
  --index-config src/main/resources/cassandra-configs/vector-index-siftsmall.yml \
  --batch-size 100 \
  --concurrency 8
```

**Expected output:**
```
Connected to cluster: Test Cluster [127.0.0.1:9042]
Creating keyspace: jvector_bench
Creating table: vectors
Creating vector index with config:
  - similarity_function: EUCLIDEAN
  - maximum_node_connections: 16
  - construction_beam_width: 100

Loading dataset siftsmall (10000 vectors)...
Loaded 10000 vectors...
Dataset loaded in 5.2 seconds (1923 vectors/sec)

Waiting for index build to complete...
Index build complete (estimated 2.1 seconds)

Total time: 7.3 seconds
```

**Verify data loaded:**
```bash
docker exec -it cassandra cqlsh -e "SELECT COUNT(*) FROM jvector_bench.vectors;"
```

Should show: `10000`

### 5. Run Cassandra Benchmarks

```bash
java -jar target/jvector-bench-1.0.0-SNAPSHOT-jar-with-dependencies.jar cassandra benchmark \
  --connection src/main/resources/cassandra-configs/connection-local.yml \
  --dataset siftsmall \
  --output results/cassandra-siftsmall \
  --topK 10 \
  --query-runs 2
```

**Expected output:**
```
Connected to cluster: Test Cluster [127.0.0.1:9042]
Using keyspace: jvector_bench
Loading query vectors from dataset: siftsmall (100 queries)

Running CassandraThroughputBenchmark...
Warming up with 3 runs...
Test run 0: 1234.5 QPS
Test run 1: 1256.3 QPS
Test run 2: 1248.7 QPS
Average: 1246.5 ± 11.2 QPS (CV: 0.9%)

Running CassandraLatencyBenchmark...
Mean: 0.805ms, p50: 0.789ms, p95: 1.234ms, p99: 2.456ms, p999: 3.123ms

Running CassandraAccuracyBenchmark...
Recall@10: 0.9456, MAP@10: 0.8923

Results written to:
  - results/cassandra-siftsmall.csv
  - results/cassandra-siftsmall.json
```

**Success criteria:**
- ✅ All three benchmarks complete
- ✅ QPS > 500 (for local single-node)
- ✅ Recall@10 > 0.85
- ✅ CSV and JSON files created

### 6. Test with Larger Dataset

Load and benchmark a 1M vector dataset:

```bash
# Load dataset
java -Xmx8g -jar target/jvector-bench-1.0.0-SNAPSHOT-jar-with-dependencies.jar cassandra load \
  --connection src/main/resources/cassandra-configs/connection-local.yml \
  --dataset cap-1M \
  --index-config src/main/resources/cassandra-configs/vector-index-ada002.yml \
  --batch-size 500 \
  --concurrency 32 \
  --drop-existing

# Run benchmarks
java -Xmx8g -jar target/jvector-bench-1.0.0-SNAPSHOT-jar-with-dependencies.jar cassandra benchmark \
  --connection src/main/resources/cassandra-configs/connection-local.yml \
  --dataset cap-1M \
  --output results/cassandra-cap-1M
```

**Note:** Loading 1M vectors takes 2-5 minutes depending on hardware.

### 7. Test Different Index Configurations

Test different M (maximum_node_connections) values:

**Create config with M=8:**
```yaml
# vector-index-m8.yml
vector_index:
  dimension: 1536
  similarity_function: "DOT_PRODUCT"
  maximum_node_connections: 8
  construction_beam_width: 100
  source_model: "OPENAI_V3_LARGE"
```

**Create config with M=32:**
```yaml
# vector-index-m32.yml
vector_index:
  dimension: 1536
  similarity_function: "DOT_PRODUCT"
  maximum_node_connections: 32
  construction_beam_width: 100
  source_model: "OPENAI_V3_LARGE"
```

**Test each configuration:**
```bash
# Test M=8
java -jar target/jvector-bench-1.0.0-SNAPSHOT-jar-with-dependencies.jar cassandra load \
  --connection src/main/resources/cassandra-configs/connection-local.yml \
  --dataset cap-1M \
  --index-config vector-index-m8.yml \
  --drop-existing

java -jar target/jvector-bench-1.0.0-SNAPSHOT-jar-with-dependencies.jar cassandra benchmark \
  --connection src/main/resources/cassandra-configs/connection-local.yml \
  --dataset cap-1M \
  --output results/cassandra-cap-1M-m8

# Test M=32
java -jar target/jvector-bench-1.0.0-SNAPSHOT-jar-with-dependencies.jar cassandra load \
  --connection src/main/resources/cassandra-configs/connection-local.yml \
  --dataset cap-1M \
  --index-config vector-index-m32.yml \
  --drop-existing

java -jar target/jvector-bench-1.0.0-SNAPSHOT-jar-with-dependencies.jar cassandra benchmark \
  --connection src/main/resources/cassandra-configs/connection-local.yml \
  --dataset cap-1M \
  --output results/cassandra-cap-1M-m32
```

**Compare results:**
- M=8: Faster build, lower recall, higher QPS
- M=32: Slower build, higher recall, lower QPS

---

## Comparing Results

### 1. Run Both JVector and Cassandra Benchmarks

```bash
# Run JVector direct benchmark
java -Xmx8g -jar target/jvector-bench-1.0.0-SNAPSHOT-jar-with-dependencies.jar jvector \
  --output results/jvector-cap-1M \
  cap-1M

# Run Cassandra benchmark (assuming data already loaded)
java -Xmx8g -jar target/jvector-bench-1.0.0-SNAPSHOT-jar-with-dependencies.jar cassandra benchmark \
  --connection src/main/resources/cassandra-configs/connection-local.yml \
  --dataset cap-1M \
  --output results/cassandra-cap-1M
```

### 2. Use Comparison Tool

Generate an HTML comparison report:

```bash
java -jar target/jvector-bench-1.0.0-SNAPSHOT-jar-with-dependencies.jar cassandra compare \
  --jvector results/jvector-cap-1M.json \
  --cassandra results/cassandra-cap-1M.json \
  --output results/comparison-report.html
```

**Other formats:**
```bash
# JSON format
java -jar target/jvector-bench-1.0.0-SNAPSHOT-jar-with-dependencies.jar cassandra compare \
  --jvector results/jvector-cap-1M.json \
  --cassandra results/cassandra-cap-1M.json \
  --output results/comparison-report.json \
  --format json

# Markdown format
java -jar target/jvector-bench-1.0.0-SNAPSHOT-jar-with-dependencies.jar cassandra compare \
  --jvector results/jvector-cap-1M.json \
  --cassandra results/cassandra-cap-1M.json \
  --output results/comparison-report.md \
  --format markdown
```

### 3. Manual Comparison

Compare CSV files side-by-side:

```bash
# View JVector results
cat results/jvector-cap-1M.csv

# View Cassandra results
cat results/cassandra-cap-1M.csv

# Side-by-side comparison
paste results/jvector-cap-1M.csv results/cassandra-cap-1M.csv
```

### 4. Expected Performance Differences

Based on testing, typical overhead for Cassandra vs JVector direct:

| Metric | Expected Cassandra Overhead | Notes |
|--------|---------------------------|-------|
| QPS | -20% to -40% | Network + CQL parsing + coordination |
| Mean Latency | +15% to +50% | Depends on network latency |
| p99 Latency | +50% to +150% | Tail latencies affected by GC, network |
| Recall@K | 0% (identical) | **Should match exactly** |
| Index Build Time | +10% to +30% | Background building, memtable overhead |

**Critical:** If Recall differs significantly (>1%), investigate immediately:
- Verify index parameters match exactly
- Check similarity function is identical
- Ensure dataset versions match
- Review Cassandra logs for errors

---

## Troubleshooting

### JVector Direct Testing Issues

#### Dataset Not Found
```
ERROR: Cannot find dataset siftsmall
```

**Solution:**
- Datasets are auto-downloaded to `~/.cache/jvector-bench/` or configured path
- Check internet connection
- Verify AWS credentials if accessing private datasets
- Check disk space

#### OutOfMemoryError
```
java.lang.OutOfMemoryError: Java heap space
```

**Solution:**
```bash
# Increase heap size
java -Xmx16g -jar target/jvector-bench-*-jar-with-dependencies.jar jvector ...
```

Recommended heap sizes:
- siftsmall: 2GB (`-Xmx2g`)
- 1M vectors: 8GB (`-Xmx8g`)
- 6M vectors: 16GB (`-Xmx16g`)
- 10M vectors: 32GB (`-Xmx32g`)

#### Low Performance
```
QPS: 500 (expected: >10000)
```

**Possible causes:**
- CPU throttling (check system load)
- Disk I/O bottleneck (use SSD)
- Insufficient heap memory
- Background processes consuming resources

### Cassandra Testing Issues

#### Connection Refused
```
ERROR: All host(s) tried for query failed
```

**Solution:**
1. Verify Cassandra is running:
   ```bash
   docker ps
   # OR
   ccm status
   ```

2. Check port 9042:
   ```bash
   netstat -an | grep 9042
   ```

3. Wait longer for Cassandra to start (60+ seconds):
   ```bash
   docker logs cassandra
   ```

4. Verify datacenter name:
   ```bash
   docker exec cassandra nodetool status
   ```

#### Index Build Timeout
```
ERROR: Timeout waiting for index build
```

**Solution:**
- Large datasets take time to index (be patient)
- Use `--skip-index-wait` flag and check manually later
- Check Cassandra logs: `docker logs cassandra`
- Verify adequate resources (CPU, memory, disk I/O)
- Check index status in cqlsh:
  ```sql
  SELECT index_name, index_status FROM system_schema.indexes 
  WHERE keyspace_name = 'jvector_bench';
  ```

#### Low Cassandra Performance
```
QPS: 50 (expected: >500 for local)
```

**Possible causes:**
1. **Network latency** (even for localhost):
   - Test with `ping 127.0.0.1`
   - Check Docker networking overhead

2. **Inadequate connection pool**:
   - Increase in `connection-local.yml`:
     ```yaml
     connection:
       max_requests_per_connection: 2048
       pool_local_size: 8
     ```

3. **Cassandra resource constraints**:
   ```bash
   docker stats cassandra
   docker exec cassandra nodetool tpstats
   ```

4. **Disk I/O bottleneck**:
   - Use SSD storage
   - Check Docker volume performance

5. **JVM heap too small**:
   ```bash
   java -Xmx16g -jar ...
   ```

#### Accuracy Mismatch
```
Cassandra Recall@10: 0.45 (JVector: 0.95)
```

**CRITICAL - Investigate immediately:**

1. **Verify index parameters match:**
   ```bash
   # Check index config
   docker exec -it cassandra cqlsh -e "DESCRIBE INDEX jvector_bench.vectors_ann_idx;"
   ```

2. **Check similarity function:**
   - Must be identical (DOT_PRODUCT, COSINE, or EUCLIDEAN)

3. **Verify all data loaded:**
   ```bash
   docker exec -it cassandra cqlsh -e "SELECT COUNT(*) FROM jvector_bench.vectors;"
   ```

4. **Check for index build errors:**
   ```bash
   docker logs cassandra | grep -i error
   ```

5. **Verify dataset versions match:**
   - Ensure same dataset file used for both tests

#### Data Loading Slow
```
Loading 1M vectors taking >10 minutes
```

**Solution:**
- Increase batch size: `--batch-size 1000`
- Increase concurrency: `--concurrency 64`
- Check Cassandra write performance: `nodetool tpstats`
- Verify disk I/O isn't saturated

### General Issues

#### Build Failures
```
[ERROR] Failed to execute goal ... compilation failure
```

**Solution:**
1. Verify JDK 20+ installed:
   ```bash
   javac -version
   ```

2. Install JVector dependencies:
   ```bash
   cd /path/to/jvector
   ./mvnw clean install -DskipTests
   ```

3. Clean and rebuild:
   ```bash
   ./mvnw clean package
   ```

#### Checkpoint File Corruption
```
ERROR: Failed to read checkpoint file
```

**Solution:**
```bash
# Delete checkpoint and restart
rm results/output.checkpoint.json
```

---

## Testing Checklist

### JVector Direct Testing
- [ ] Build project successfully
- [ ] Run siftsmall benchmark
- [ ] Run 1M dataset benchmark
- [ ] Test multiple datasets
- [ ] Test custom configuration
- [ ] Test checkpoint/resume
- [ ] Verify output files (CSV, JSON)
- [ ] Check recall > 0.90

### Cassandra Integration Testing
- [ ] Deploy Cassandra (Docker or CCM)
- [ ] Verify connection
- [ ] Load siftsmall dataset
- [ ] Run siftsmall benchmarks
- [ ] Load 1M dataset
- [ ] Run 1M benchmarks
- [ ] Test different index configs
- [ ] Verify data loaded correctly
- [ ] Check recall matches JVector direct

### Comparison Testing
- [ ] Run both JVector and Cassandra benchmarks
- [ ] Generate comparison report
- [ ] Verify recall matches (±1%)
- [ ] Document performance overhead
- [ ] Analyze latency differences

---

## Next Steps

After successful testing:

1. **Document your findings** - Record performance metrics
2. **Test different configurations** - Experiment with M, efConstruction values
3. **Scale testing** - Try larger datasets (6M, 10M)
4. **Production validation** - Test against real Cassandra cluster
5. **Automate** - Create scripts for regression testing

---

## Getting Help

If you encounter issues not covered here:

1. **Check logs:**
   - JVector: Console output with `--diag 2`
   - Cassandra: `docker logs cassandra`

2. **Review documentation:**
   - README.md for command reference
   - Configuration examples in `src/main/resources/cassandra-configs/`

3. **Verify basics:**
   - Java version
   - Cassandra status
   - Network connectivity
   - Disk space

4. **File an issue:**
   - Include error messages
   - Provide configuration files
   - Share benchmark results
   - Describe steps to reproduce

Good luck with testing! 🚀
