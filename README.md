# JVector Benchmark Suite

Standalone benchmark and regression testing suite for JVector, extracted from the jvector-examples module.

## Overview

This project contains the AutoBenchYAML benchmark runner, which is used for automated regression testing of JVector performance. It has been extracted from the main JVector repository to function as a standalone project that depends on JVector as a library dependency.

## Project Structure

```
jvector-bench/
├── pom.xml                                    # Maven project configuration
├── src/main/java/io/github/jbellis/jvector/bench/
│   ├── AutoBenchYAML.java                     # Main benchmark runner
│   ├── Grid.java                              # Core benchmark execution logic
│   ├── BenchResult.java                       # Result data structure
│   ├── benchmarks/                            # Benchmark implementations
│   │   ├── AccuracyBenchmark.java
│   │   ├── LatencyBenchmark.java
│   │   ├── ThroughputBenchmark.java
│   │   └── ...
│   ├── util/                                  # Utility classes
│   │   ├── DataSet.java
│   │   ├── DataSetLoader.java
│   │   ├── BenchmarkSummarizer.java
│   │   ├── CheckpointManager.java
│   │   └── ...
│   └── yaml/                                  # Configuration classes
│       ├── MultiConfig.java
│       ├── ConstructionParameters.java
│       ├── SearchParameters.java
│       └── ...
```

## Prerequisites

### JDK Installation

This project requires a JDK (Java Development Kit), not just a JRE. The project is configured for Java 20.

**On macOS:**
```bash
# Install using Homebrew
brew install openjdk@20

# Or download from:
# https://adoptium.net/temurin/releases/
```

**On Linux:**
```bash
# Using apt (Debian/Ubuntu)
sudo apt-get install openjdk-20-jdk

# Using yum (RedHat/CentOS)
sudo yum install java-20-openjdk-devel
```

**Verify Installation:**
```bash
javac -version  # Should show version 20.x.x
java -version   # Should show version 20.x.x
```

### Maven

The project includes a Maven wrapper (./mvnw), so you don't need to install Maven separately.

## Building the Project

### Install JVector Dependencies

Since this project depends on JVector SNAPSHOT versions, you need to first build and install JVector to your local Maven repository:

```bash
# Clone and build JVector
cd /path/to/jvector
./mvnw clean install -DskipTests
```

### Build jvector-bench

```bash
# Build the project
./mvnw clean package

# This creates:
# target/jvector-bench-1.0.0-SNAPSHOT.jar
# target/jvector-bench-1.0.0-SNAPSHOT-jar-with-dependencies.jar
```

## Running Benchmarks

### Basic Usage

```bash
java -jar target/jvector-bench-1.0.0-SNAPSHOT-jar-with-dependencies.jar \
  --output results \
  [dataset-patterns...]
```

### Command-Line Arguments

- `--output <path>`: (Required) Base path for output files. Creates:
  - `<path>.csv`: Summary results in CSV format
  - `<path>.json`: Detailed results in JSON format
  - `<path>.checkpoint.json`: Checkpoint file for resuming

- `--config <path>`: (Optional) Path to custom YAML configuration file

- `--diag <level>`: (Optional) Diagnostic level (0-3)
  - 0: No diagnostics (default)
  - 1: Basic diagnostics
  - 2: Detailed diagnostics
  - 3: Full diagnostics

- `[dataset-patterns...]`: (Optional) Regex patterns to filter datasets. If omitted, all datasets are run.

### Available Datasets

The following datasets are supported:
- cap-1M
- cap-6M
- cohere-english-v3-1M
- cohere-english-v3-10M
- dpr-1M
- dpr-10M

### Examples

**Run all datasets:**
```bash
java -jar target/jvector-bench-1.0.0-SNAPSHOT-jar-with-dependencies.jar \
  --output results/benchmark-$(date +%Y%m%d)
```

**Run specific datasets:**
```bash
java -jar target/jvector-bench-1.0.0-SNAPSHOT-jar-with-dependencies.jar \
  --output results/test \
  "cap-1M" "dpr-1M"
```

**Run with pattern matching:**
```bash
# Run all 1M datasets
java -jar target/jvector-bench-1.0.0-SNAPSHOT-jar-with-dependencies.jar \
  --output results/1M-test \
  ".*-1M"
```

**Run with custom config and diagnostics:**
```bash
java -jar target/jvector-bench-1.0.0-SNAPSHOT-jar-with-dependencies.jar \
  --output results/custom \
  --config my-config.yaml \
  --diag 2 \
  "cohere.*"
```

### Memory Configuration

For large datasets, you may need to increase heap memory:

```bash
java -Xmx16g -jar target/jvector-bench-1.0.0-SNAPSHOT-jar-with-dependencies.jar \
  --output results/large-test \
  "cap-6M"
```

## Checkpoint and Resume

AutoBenchYAML automatically creates checkpoint files that allow resuming from failures:

1. When a dataset completes, it's marked in `<output>.checkpoint.json`
2. If the benchmark is interrupted and restarted with the same `--output` path
3. Already-completed datasets will be skipped
4. Processing continues from the next dataset

**Example:**
```bash
# First run (interrupted after cap-1M)
java -jar target/jvector-bench-1.0.0-SNAPSHOT-jar-with-dependencies.jar \
  --output results/test

# Resume from checkpoint (skips cap-1M, continues with next)
java -jar target/jvector-bench-1.0.0-SNAPSHOT-jar-with-dependencies.jar \
  --output results/test
```

## Configuration

### Default Configuration

If no `--config` file is specified, AutoBenchYAML uses a default configuration called "autoDefault" with standard parameters.

### Custom Configuration

Create a YAML file with your custom parameters:

```yaml
dataset: my-dataset  # Can be overridden by command-line dataset pattern

construction:
  outDegree: [16, 32]
  efConstruction: [100, 200]
  neighborOverflow: [1.2]
  addHierarchy: [true]
  # ... other construction parameters

search:
  topKOverquery:
    10: [40, 80]
    100: [100, 200]
  useSearchPruning: [true]
  # ... other search parameters
```

See the `yaml` package classes for all available configuration options.

## Output Format

### CSV Output (`<output>.csv`)

Summary statistics by dataset:
```csv
dataset,QPS,QPS StdDev,Mean Latency,Recall@10,Index Construction Time
cap-1M,12345.67,234.56,0.081,0.95,45.2
...
```

### JSON Output (`<output>.json`)

Detailed results with full parameters and metrics for each benchmark run:
```json
[
  {
    "dataset": "cap-1M",
    "parameters": {
      "M": 16,
      "efConstruction": 100,
      ...
    },
    "metrics": {
      "qps": 12345.67,
      "recall": 0.95,
      ...
    }
  },
  ...
]
```

## Dependencies

This project depends on JVector modules:
- `jvector-base`: Core JVector functionality
- `jvector-twenty`: Vector API support for JDK 20+
- `jvector-native`: Native SIMD support

External dependencies:
- Jackson: JSON/YAML parsing
- SLF4J + Logback: Logging
- AWS SDK: S3 dataset downloading
- jhdf: HDF5 dataset support
- util-mmap: Memory-mapped file support

## Troubleshooting

### "No compiler is provided in this environment"

You're running with a JRE instead of a JDK. Install a JDK (see Prerequisites section).

### "Could not resolve dependencies for project"

Run `./mvnw clean install -DskipTests` in the jvector project directory first to install JVector to your local Maven repository.

### OutOfMemoryError

Increase heap size with `-Xmx` flag:
```bash
java -Xmx16g -jar target/jvector-bench-1.0.0-SNAPSHOT-jar-with-dependencies.jar ...
```

### Dataset Download Issues

Datasets are automatically downloaded from S3. If you have network issues:
1. Check your internet connection
2. Verify AWS credentials if accessing private datasets
3. Manually download datasets and place them in the expected location

## Development

### Running from IDE

Main class: `io.github.datastax.jvector.bench.JvectorBench`

VM options: `-Xmx16g`

Program arguments: `--output results/test cap-1M`

### Adding New Benchmarks

1. Implement the benchmark in the `benchmarks` package
2. Add it to the grid execution in `Grid.java`
3. Update result collection in `BenchResult.java` if needed

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