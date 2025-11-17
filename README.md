# JVector Benchmark Suite

Standalone benchmark and regression testing suite for JVector, providing performance testing for both direct JVector usage and Cassandra integration.

## Overview

This project provides comprehensive benchmarking tools for JVector vector search performance:
- **JVector Direct Benchmarks**: Test JVector library performance directly
- **Cassandra Benchmarks**: Test JVector performance through Cassandra's vector search capabilities
- **Comparison Tools**: Compare results between direct and Cassandra implementations

## Prerequisites

### Required
- **JDK 20+** (not just JRE)
- **Maven** (wrapper included: `./mvnw`)

### For Cassandra Testing
- **Cassandra 5.0+** with SAI vector search support
- **Docker** (recommended) or **CCM** for local Cassandra deployment

### JDK Installation

**macOS:**
```bash
brew install openjdk@20
```

**Linux (Debian/Ubuntu):**
```bash
sudo apt-get install openjdk-20-jdk
```

**Verify:**
```bash
javac -version  # Should show version 20.x.x
java -version   # Should show version 20.x.x
```

## Building the Project

### Install JVector Dependencies

Since this project depends on JVector SNAPSHOT versions, first build and install JVector:

```bash
cd /path/to/jvector
./mvnw clean install -DskipTests
```

### Build jvector-bench

```bash
cd /path/to/jvector-bench
./mvnw clean package
```

This creates:
- `target/jvector-bench-1.0.0-SNAPSHOT.jar`
- `target/jvector-bench-1.0.0-SNAPSHOT-jar-with-dependencies.jar`

## Quick Start

### JVector Direct Benchmarks

Run benchmarks directly against JVector library:

```bash
java -jar target/jvector-bench-1.0.0-SNAPSHOT-jar-with-dependencies.jar jvector \
  --output results/benchmark-$(date +%Y%m%d) \
  cap-1M
```

### Cassandra Benchmarks

1. **Start Cassandra:**
```bash
docker run -d --name cassandra -p 9042:9042 -e CASSANDRA_DC=datacenter1 cassandra:5.0
```

2. **Load dataset:**
```bash
java -jar target/jvector-bench-1.0.0-SNAPSHOT-jar-with-dependencies.jar cassandra load \
  --connection src/main/resources/cassandra-configs/connection-local.yml \
  --dataset cap-1M \
  --index-config src/main/resources/cassandra-configs/vector-index-ada002.yml
```

3. **Run benchmarks:**
```bash
java -jar target/jvector-bench-1.0.0-SNAPSHOT-jar-with-dependencies.jar cassandra benchmark \
  --connection src/main/resources/cassandra-configs/connection-local.yml \
  --dataset cap-1M \
  --output results/cassandra-cap-1M
```

## Command Reference

### JVector Direct Mode

**Basic Usage:**
```bash
java -jar jvector-bench-*-jar-with-dependencies.jar jvector \
  --output <path> \
  [--config <yaml>] \
  [--diag <level>] \
  [dataset-patterns...]
```

**Arguments:**
- `--output <path>`: (Required) Base path for output files (creates `.csv`, `.json`, `.checkpoint.json`)
- `--config <path>`: (Optional) Custom YAML configuration file
- `--diag <level>`: (Optional) Diagnostic level (0-3, default: 0)
- `[dataset-patterns...]`: (Optional) Regex patterns to filter datasets (runs all if omitted)

**Examples:**
```bash
# Run all datasets
java -jar jvector-bench-*-jar-with-dependencies.jar jvector --output results/all

# Run specific datasets
java -jar jvector-bench-*-jar-with-dependencies.jar jvector --output results/test cap-1M dpr-1M

# Run with pattern matching (all 1M datasets)
java -jar jvector-bench-*-jar-with-dependencies.jar jvector --output results/1M ".*-1M"

# Run with custom config and diagnostics
java -jar jvector-bench-*-jar-with-dependencies.jar jvector \
  --output results/custom \
  --config my-config.yaml \
  --diag 2 \
  "cohere.*"
```

### Cassandra Mode

**Load Dataset:**
```bash
java -jar jvector-bench-*-jar-with-dependencies.jar cassandra load \
  --connection <connection.yml> \
  --dataset <dataset-name> \
  --index-config <index.yml> \
  [--batch-size <n>] \
  [--concurrency <n>] \
  [--drop-existing]
```

**Run Benchmarks:**
```bash
java -jar jvector-bench-*-jar-with-dependencies.jar cassandra benchmark \
  --connection <connection.yml> \
  --dataset <dataset-name> \
  --output <path> \
  [--topK <n>] \
  [--query-runs <n>]
```

**Compare Results:**
```bash
java -jar jvector-bench-*-jar-with-dependencies.jar cassandra compare \
  --jvector <jvector-results.json> \
  --cassandra <cassandra-results.json> \
  --output <report-path> \
  [--format <html|json|markdown>]
```

## Available Datasets

- `cap-1M` - CLIP dataset with 1M vectors
- `cap-6M` - CLIP dataset with 6M vectors
- `cohere-english-v3-1M` - Cohere English v3 with 1M vectors
- `cohere-english-v3-10M` - Cohere English v3 with 10M vectors
- `dpr-1M` - DPR dataset with 1M vectors
- `dpr-10M` - DPR dataset with 10M vectors
- `siftsmall` - Small SIFT dataset (10K vectors, good for testing)

Datasets are automatically downloaded from S3 when first used.

## Output Files

### CSV Output (`<output>.csv`)
Summary statistics by dataset:
```csv
dataset,QPS,QPS StdDev,Mean Latency,Recall@10,Index Construction Time
cap-1M,12345.67,234.56,0.081,0.95,45.2
```

### JSON Output (`<output>.json`)
Detailed results with full parameters and metrics for each benchmark run.

### Checkpoint File (`<output>.checkpoint.json`)
Tracks completed datasets for resuming interrupted runs.

## Checkpoint and Resume

Benchmarks automatically create checkpoint files. If interrupted:
1. Completed datasets are marked in `<output>.checkpoint.json`
2. Rerun with the same `--output` path
3. Already-completed datasets are skipped
4. Processing continues from the next dataset

## Configuration

### Default Configuration
If no `--config` file is specified, uses "autoDefault" configuration with standard parameters.

### Custom Configuration
Create a YAML file with custom parameters:

```yaml
dataset: my-dataset

construction:
  outDegree: [16, 32]
  efConstruction: [100, 200]
  neighborOverflow: [1.2]
  addHierarchy: [true]

search:
  topKOverquery:
    10: [40, 80]
    100: [100, 200]
  useSearchPruning: [true]
```

### Cassandra Connection Configuration
Example `cassandra-connection.yml`:

```yaml
cassandra:
  contact_points:
    - "127.0.0.1:9042"
  local_datacenter: "datacenter1"
  keyspace: "jvector_bench"
  replication_factor: 1
  
  # Optional: authentication
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

### Vector Index Configuration
Example `vector-index-config.yml`:

```yaml
vector_index:
  dimension: 1536
  similarity_function: "DOT_PRODUCT"  # DOT_PRODUCT, COSINE, EUCLIDEAN
  maximum_node_connections: 16        # M parameter
  construction_beam_width: 100        # efConstruction
  source_model: "OPENAI_V3_LARGE"
```

## Memory Configuration

For large datasets, increase heap memory:

```bash
java -Xmx16g -jar target/jvector-bench-*-jar-with-dependencies.jar jvector \
  --output results/large-test \
  "cap-6M"
```

## Testing

See [TESTING_GUIDE.md](TESTING_GUIDE.md) for detailed instructions on:
- Running JVector direct tests
- Setting up and testing Cassandra integration
- Comparing results between implementations
- Troubleshooting common issues

## Troubleshooting

### "No compiler is provided in this environment"
You're running with a JRE instead of a JDK. Install a JDK (see Prerequisites).

### "Could not resolve dependencies for project"
Run `./mvnw clean install -DskipTests` in the jvector project directory first.

### OutOfMemoryError
Increase heap size: `java -Xmx16g -jar ...`

### Dataset Download Issues
Datasets are automatically downloaded from S3. Check:
1. Internet connection
2. AWS credentials (if accessing private datasets)
3. Disk space for dataset storage

### Cassandra Connection Issues
- Verify Cassandra is running: `docker ps` or `ccm status`
- Check port 9042 is accessible
- Verify datacenter name matches cluster configuration
- Wait for Cassandra to fully start (can take 60+ seconds)

## Development

### Running from IDE

**JVector benchmarks:**
- Main class: `io.github.datastax.jvector.bench.BenchmarkLauncher`
- VM options: `-Xmx16g`
- Program arguments: `jvector --output results/test cap-1M`

**Cassandra benchmarks:**
- Main class: `io.github.datastax.jvector.bench.BenchmarkLauncher`
- VM options: `-Xmx16g`
- Program arguments: `cassandra benchmark --connection conn.yml --dataset cap-1M --output results/test`

**Alternative (direct class execution):**
- `io.github.datastax.jvector.bench.JvectorBench` with args: `--output results/test cap-1M`
- `io.github.datastax.jvector.bench.cassandra.CassandraBenchmarkRunner` with args: `benchmark --connection conn.yml --dataset cap-1M --output results/test`

## Project Structure

```
jvector-bench/
├── pom.xml
├── src/main/java/io/github/datastax/jvector/bench/
│   ├── BenchmarkLauncher.java          # Unified entry point
│   ├── JvectorBench.java               # JVector direct benchmarks
│   ├── Grid.java                       # Core benchmark execution
│   ├── BenchResult.java                # Result data structure
│   ├── benchmarks/                     # JVector benchmark implementations
│   │   ├── AccuracyBenchmark.java
│   │   ├── LatencyBenchmark.java
│   │   ├── ThroughputBenchmark.java
│   │   └── ...
│   ├── cassandra/                      # Cassandra integration
│   │   ├── CassandraBenchmarkRunner.java
│   │   ├── CassandraConnection.java
│   │   ├── benchmarks/                 # Cassandra-specific benchmarks
│   │   ├── comparison/                 # Comparison tools
│   │   └── config/                     # Cassandra configuration
│   ├── util/                           # Utility classes
│   │   ├── DataSet.java
│   │   ├── DataSetLoader.java
│   │   └── ...
│   └── yaml/                           # Configuration classes
│       ├── MultiConfig.java
│       └── ...
└── src/main/resources/
    ├── cassandra-configs/              # Example Cassandra configs
    └── yaml-configs/                   # Example JVector configs
```

## Dependencies

**JVector modules:**
- `jvector-base`: Core JVector functionality
- `jvector-twenty`: Vector API support for JDK 20+
- `jvector-native`: Native SIMD support

**External dependencies:**
- Jackson: JSON/YAML parsing
- SLF4J + Logback: Logging
- AWS SDK: S3 dataset downloading
- jhdf: HDF5 dataset support
- DataStax Java Driver: Cassandra connectivity

## License

Copyright DataStax, Inc.

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.

## Contributing

This is a standalone extraction from the main JVector project. For issues related to:
- JVector core functionality: https://github.com/jbellis/jvector
- Benchmark suite: Create issues in this repository

## Links

- JVector Project: https://github.com/jbellis/jvector
- JVector Documentation: https://github.com/jbellis/jvector/wiki