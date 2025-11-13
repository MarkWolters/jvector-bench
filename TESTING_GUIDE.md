# Cassandra Benchmark Testing Guide

## Quick Start: Testing Phase 2

This guide walks you through testing the Cassandra benchmark framework end-to-end.

---

## Prerequisites

1. **Java 20+** installed
2. **Maven** installed
3. **Docker** (for Cassandra) OR **CCM** (Cassandra Cluster Manager)
4. **Dataset files** - siftsmall is recommended for initial testing

---

## Step-by-Step Testing

### 1. Build the Project

```bash
cd /Users/markwolters/dev/repos/jvector-bench
mvn clean package
```

**Expected output:**
```
[INFO] BUILD SUCCESS
[INFO] Total time: XX.XXX s
```

**Troubleshooting:**
- If compilation fails, check Java version: `java -version`
- If missing dependencies, try: `mvn clean install`

---

### 2. Deploy Cassandra

**Option A: Docker (Easiest)**
```bash
# Start Cassandra
docker run -d --name cassandra \
  -p 9042:9042 \
  -e CASSANDRA_DC=datacenter1 \
  cassandra:5.0

# Wait for it to be ready (takes ~30-60 seconds)
sleep 60

# Check status
docker exec cassandra nodetool status
```

**Expected output:**
```
Datacenter: datacenter1
=======================
Status=Up/Down
|/ State=Normal/Leaving/Joining/Moving
--  Address    Load       Tokens  Owns    Host ID                               Rack
UN  127.0.0.1  XXX KB     256     100.0%  xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx  rack1
```

**Option B: CCM (For Multi-Node Testing)**
```bash
# Create cluster
ccm create bench_cluster -v 5.0 -n 1
ccm start

# Check status
ccm status
```

**Troubleshooting:**
- If Docker isn't starting: `docker logs cassandra`
- If port 9042 is in use: Find and stop other Cassandra instances
- If CCM fails: Install with `pip install ccm`

---

### 3. Verify Connection

Create a simple test to verify Cassandra is accessible:

```bash
# Using cqlsh (if available)
docker exec -it cassandra cqlsh
# Then run: DESCRIBE KEYSPACES;
# Press Ctrl+D to exit

# OR check with nodetool
docker exec cassandra nodetool statusgossip
```

---

### 4. Prepare Configuration Files

The example configs are already in place, but verify they exist:

```bash
ls -la src/main/resources/cassandra-configs/
```

**Should see:**
```
connection-local.yml
connection-cluster.yml
vector-index-ada002.yml
vector-index-cohere-v3.yml
vector-index-openai-v3-large.yml
```

**For testing, use:**
- `connection-local.yml` - Connection config
- Create a test index config (see below)

**Create test config for siftsmall:**

`src/main/resources/cassandra-configs/vector-index-siftsmall.yml`
```yaml
vector_index:
  dimension: 128
  similarity_function: "EUCLIDEAN"
  maximum_node_connections: 16
  construction_beam_width: 100
  source_model: "OTHER"
  enable_hierarchy: false
```

---

### 5. Load Dataset (siftsmall)

```bash
java -cp target/jvector-bench-1.0.0-SNAPSHOT-jar-with-dependencies.jar \
  io.github.datastax.jvector.bench.cassandra.CassandraBenchmarkRunner load \
  --connection src/main/resources/cassandra-configs/connection-local.yml \
  --dataset siftsmall \
  --index-config src/main/resources/cassandra-configs/vector-index-siftsmall.yml \
  --batch-size 100 \
  --concurrency 8
```

**Expected output:**
```
INFO  CassandraBenchmarkRunner - Loading dataset siftsmall into Cassandra
INFO  CassandraConnection - Connecting to Cassandra cluster...
INFO  CassandraConnection - Connected to cluster: Test Cluster [127.0.0.1:9042]
INFO  CassandraConnection - Setting up schema...
INFO  CassandraConnection - Using keyspace: jvector_bench
INFO  CassandraConnection - Schema ready with config: VectorIndexConfig{...}
INFO  DataSetLoader - Loading dataset from disk...
INFO  CassandraBenchmarkRunner - Dataset loaded: 10000 vectors, dimension 128
INFO  CassandraBenchmarkRunner - Loading 10000 vectors into Cassandra...
INFO  CassandraBenchmarkRunner - Loaded 0 / 10000 vectors (0 %)
INFO  CassandraBenchmarkRunner - Data loaded in 5.23 seconds (1912 vectors/sec)
INFO  CassandraConnection - Waiting for index build to complete...
INFO  CassandraConnection - Index 'vectors_ann_idx' is ready
INFO  CassandraBenchmarkRunner - Dataset loading complete!
```

**Troubleshooting:**
- **"Dataset not found":** Check that siftsmall.hdf5 is in your dataset directory
- **"Connection refused":** Cassandra isn't running or isn't ready yet
- **"Timeout waiting for index":** Index build is taking longer, wait more or check Cassandra logs

**Verify data loaded:**
```bash
docker exec -it cassandra cqlsh -e "SELECT COUNT(*) FROM jvector_bench.vectors;"
```

Should show: `10000`

---

### 6. Run Benchmarks

```bash
java -cp target/jvector-bench-1.0.0-SNAPSHOT-jar-with-dependencies.jar \
  io.github.datastax.jvector.bench.cassandra.CassandraBenchmarkRunner benchmark \
  --connection src/main/resources/cassandra-configs/connection-local.yml \
  --dataset siftsmall \
  --output results/cassandra-siftsmall \
  --topK 10 \
  --query-runs 2
```

**Expected output:**
```
INFO  CassandraBenchmarkRunner - Running benchmarks against Cassandra
INFO  CassandraBenchmarkRunner - Dataset: siftsmall
INFO  CassandraBenchmarkRunner - TopK: 10, Query runs: 2
INFO  DataSetLoader - Loading dataset from disk...
INFO  CassandraBenchmarkRunner - Loaded 100 query vectors

INFO  CassandraBenchmarkRunner - Running CassandraThroughputBenchmark...
INFO  CassandraThroughputBenchmark - Running throughput benchmark with 100 queries
INFO  CassandraThroughputBenchmark - Warming up with 3 runs...
INFO  CassandraThroughputBenchmark - Running 3 test runs...
INFO  CassandraThroughputBenchmark - Test run 0: 1234.5 QPS
INFO  CassandraThroughputBenchmark - Test run 1: 1256.3 QPS
INFO  CassandraThroughputBenchmark - Test run 2: 1248.7 QPS
INFO  CassandraThroughputBenchmark - Throughput benchmark complete: 1246.5 ± 11.2 QPS (CV: 0.9%)
INFO  CassandraBenchmarkRunner - CassandraThroughputBenchmark complete

INFO  CassandraBenchmarkRunner - Running CassandraLatencyBenchmark...
INFO  CassandraLatencyBenchmark - Running latency benchmark with 100 queries across 2 runs
INFO  CassandraLatencyBenchmark - Latency benchmark complete: mean=0.805ms, p50=0.789ms, p99=1.234ms, p999=2.456ms
INFO  CassandraBenchmarkRunner - CassandraLatencyBenchmark complete

INFO  CassandraBenchmarkRunner - Running CassandraAccuracyBenchmark...
INFO  CassandraAccuracyBenchmark - Running accuracy benchmark with 100 queries
INFO  CassandraAccuracyBenchmark - Accuracy benchmark complete: Recall@10=0.9456, MAP@10=0.8923
INFO  CassandraBenchmarkRunner - CassandraAccuracyBenchmark complete

INFO  CassandraBenchmarkRunner - Results written to results/cassandra-siftsmall.json
INFO  CassandraBenchmarkRunner - CSV written to results/cassandra-siftsmall.csv
```

**Troubleshooting:**
- **Low QPS (<100):** Network issues or Cassandra overloaded
- **High latency (>10ms):** Check Cassandra isn't under load
- **Low recall (<0.7):** Index configuration mismatch or data loading issue

---

### 7. View Results

**CSV:**
```bash
cat results/cassandra-siftsmall.csv
```

**JSON:**
```bash
cat results/cassandra-siftsmall.json | jq '.'
```

---

### 8. Compare with JVector-Direct

Run the same test with jvector-direct:

```bash
java -jar target/jvector-bench-1.0.0-SNAPSHOT-jar-with-dependencies.jar \
  --output results/jvector-siftsmall \
  siftsmall
```

**Compare results:**
```bash
# View Cassandra results
cat results/cassandra-siftsmall.csv

# View JVector-direct results
cat results/jvector-siftsmall.csv

# Compare side-by-side
paste results/jvector-siftsmall.csv results/cassandra-siftsmall.csv
```

**Expected differences:**
- **QPS:** Cassandra 20-40% slower
- **Latency:** Cassandra 15-50% higher
- **Recall:** Should be nearly identical (±0.01)

---

## Success Criteria

✅ **Phase 2 is working if:**

1. **Build succeeds** - Maven compiles without errors
2. **Cassandra connects** - Connection established successfully
3. **Data loads** - All 10,000 vectors inserted
4. **Index builds** - vectors_ann_idx created and ready
5. **Benchmarks run** - All three benchmarks execute
6. **Results written** - CSV and JSON files created
7. **Recall reasonable** - Recall@10 > 0.85 for siftsmall
8. **Performance measured** - QPS, latency, accuracy all reported

---

## Common Issues

### 1. Dataset Not Found
```
ERROR: Cannot find dataset siftsmall
```

**Solution:**
- Check dataset location: Usually in `~/.cache/jvector-bench/` or configured path
- Download if needed: Check jvector-bench dataset download mechanism
- Verify path in `DataSetLoader`

### 2. Cassandra Connection Refused
```
ERROR: All host(s) tried for query failed
```

**Solution:**
- Verify Cassandra is running: `docker ps`
- Check port 9042: `netstat -an | grep 9042`
- Wait longer for Cassandra to start (can take 60+ seconds)
- Check logs: `docker logs cassandra`

### 3. Low Performance
```
QPS: 50  (expected: >500 for local)
```

**Solution:**
- Check Cassandra isn't overloaded: `docker stats cassandra`
- Increase connection pool: Edit `connection-local.yml`
- Use smaller batch sizes: `--batch-size 50`
- Check network latency: `ping 127.0.0.1`

### 4. Accuracy Mismatch
```
Recall@10: 0.45  (expected: >0.85)
```

**Solution:**
- **CRITICAL ISSUE** - This indicates a problem
- Check index config matches dataset expectations
- Verify similarity function (EUCLIDEAN for siftsmall)
- Check all data loaded: `SELECT COUNT(*) FROM jvector_bench.vectors`
- Review Cassandra logs for index build errors

---

## Advanced Testing

### Test Larger Dataset

After siftsmall works, try cap-1M:

```bash
# Load
java -cp target/jvector-bench-*-jar-with-dependencies.jar \
  io.github.datastax.jvector.bench.cassandra.CassandraBenchmarkRunner load \
  --connection src/main/resources/cassandra-configs/connection-local.yml \
  --dataset cap-1M \
  --index-config src/main/resources/cassandra-configs/vector-index-ada002.yml \
  --batch-size 500 \
  --concurrency 32 \
  --drop-existing

# Benchmark
java -cp target/jvector-bench-*-jar-with-dependencies.jar \
  io.github.datastax.jvector.bench.cassandra.CassandraBenchmarkRunner benchmark \
  --connection src/main/resources/cassandra-configs/connection-local.yml \
  --dataset cap-1M \
  --output results/cassandra-cap-1M
```

### Test Different Configurations

Try different M values:

```yaml
# vector-index-m8.yml
vector_index:
  maximum_node_connections: 8  # Lower M = faster build, lower recall

# vector-index-m32.yml
vector_index:
  maximum_node_connections: 32  # Higher M = slower build, higher recall
```

---

## Cleanup

After testing:

```bash
# Stop Cassandra
docker stop cassandra
docker rm cassandra

# OR with CCM
ccm stop
ccm remove bench_cluster

# Clean build artifacts
mvn clean

# Remove results
rm -rf results/
```

---

## Next Steps

Once testing is successful:

1. **Document findings** - What performance did you see?
2. **Compare configurations** - Test different M, efConstruction values
3. **Scale testing** - Try larger datasets
4. **Production validation** - Test against real cluster
5. **Phase 3** - Implement automated comparison tools (optional)

---

## Getting Help

If you encounter issues:

1. **Check logs** - Look at Cassandra logs: `docker logs cassandra`
2. **Review docs** - CASSANDRA_BENCHMARK_USAGE.md has more details
3. **Debug mode** - Add `--diag 2` flag for verbose output (if implemented)
4. **Verify basics** - Connection, data loading, index creation

Good luck with testing! 🚀
