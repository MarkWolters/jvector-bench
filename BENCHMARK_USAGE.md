# JVector Benchmark Usage Guide

This guide covers two ways to run JVector benchmarks:
1. **JvectorBench** - Running benchmarks locally via command line
2. **IPCService** - Running benchmarks remotely via socket communication

---

## JvectorBench - Local Benchmark Execution

### Overview

JvectorBench is a command-line tool for running benchmarks on one or more datasets locally. It supports checkpointing to resume from failures and generates CSV and JSON output files.

### Command-Line Arguments

```bash
java -jar jvector-bench.jar --output <path> [--config <path>] [--diag <level>] <dataset1> [dataset2] ...
```

**Required Arguments:**
- `--output <path>` - Base path for output files (will generate `.csv` and `.json` files)
- `<dataset1> [dataset2] ...` - One or more dataset names to benchmark

**Optional Arguments:**
- `--config <path>` - Path to a custom YAML configuration file (uses default config if not specified)
- `--diag <level>` - Diagnostic level (integer) for detailed logging

### Output Files

JvectorBench generates three files:

1. **`<output>.csv`** - Summary statistics per dataset
   - Columns: dataset, QPS, QPS StdDev, Mean Latency, Recall@10, Index Construction Time

2. **`<output>.json`** - Detailed benchmark results for all runs

3. **`<output>.checkpoint.json`** - Checkpoint file for resuming interrupted runs

### Examples

#### Basic Usage

Run benchmarks on a single dataset:
```bash
java -jar jvector-bench.jar --output results/cap-1M cap-1M
```

#### Multiple Datasets

Run benchmarks on multiple datasets:
```bash
java -jar jvector-bench.jar --output results/benchmark cap-1M dpr-1M cohere-english-v3-1M
```

#### With Custom Configuration

Use a custom configuration file:
```bash
java -jar jvector-bench.jar --output results/custom --config configs/my-config.yml cap-1M
```

#### With Diagnostics

Enable diagnostic logging:
```bash
java -jar jvector-bench.jar --output results/debug --diag 2 cap-1M dpr-1M
```

### Checkpoint Support

If a benchmark run fails or is interrupted, JvectorBench automatically creates a checkpoint file. When you re-run the same command, it will:
- Skip datasets that have already been completed
- Resume from where it left off
- Append new results to existing completed results

This is especially useful for long-running benchmarks or when testing multiple large datasets.

---

## IPCService - Remote Benchmark Execution

### Overview

IPCService provides a socket-based interface for running benchmarks remotely. It supports the same functionality as JvectorBench but allows you to trigger benchmarks over a Unix domain socket and monitor their progress asynchronously.

### Starting the Service

Start the IPCService on a Unix domain socket:

```bash
# Default socket location: /tmp/jvector.sock
java -jar ipcservice.jar

# Custom socket location
java -jar ipcservice.jar /path/to/custom.sock
```

The service will print:
```
Service listening on /tmp/jvector.sock
```

### Command Protocol

All commands must be terminated with a newline (`\n`). Responses follow the format:
- `OK\n` - Command succeeded
- `ERROR <message>\n` - Command failed
- `RESULT <data>\n` - Command succeeded with data

### Benchmark Commands

#### 1. Configure Benchmark

Set up benchmark configuration (required first step):

```
BENCH_CONFIG --output <path> [--config <path>] [--diag <level>]
```

**Parameters:**
- `--output <path>` - Required. Base path for output files
- `--config <path>` - Optional. Path to custom YAML configuration
- `--diag <level>` - Optional. Diagnostic level (integer)

**Response:** `OK\n`

**Example:**
```
BENCH_CONFIG --output /data/results/benchmark --diag 1
```

#### 2. Add Datasets

Add one or more datasets to benchmark (can be called multiple times):

```
BENCH_ADD_DATASET <dataset1> [dataset2] [dataset3] ...
```

**Parameters:**
- Space-separated list of dataset names

**Response:** `OK\n`

**Example:**
```
BENCH_ADD_DATASET cap-1M dpr-1M
BENCH_ADD_DATASET cohere-english-v3-1M
```

#### 3. Start Benchmark

Start running benchmarks in the background:

```
BENCH_START
```

**Response:** `OK\n`

The benchmark runs asynchronously. Use `BENCH_STATUS` to monitor progress.

#### 4. Check Status

Get current benchmark status and progress:

```
BENCH_STATUS
```

**Response:** `RESULT <status> <completed>/<total> [ERROR: <message>]\n`

**Status values:**
- `NOT_CONFIGURED` - BENCH_CONFIG has not been called
- `NOT_STARTED` - Configuration set but benchmark hasn't started
- `RUNNING` - Benchmark is currently running
- `COMPLETED` - Benchmark completed successfully
- `FAILED` - Benchmark failed with error

**Example Response:**
```
RESULT RUNNING 2/5
```

#### 5. Get Results

Retrieve benchmark results in JSON format:

```
BENCH_RESULTS
```

**Response:** `RESULT <json>\n`

Returns all collected benchmark results. Can be called while benchmark is running (returns partial results) or after completion.

### Complete Workflow Example

Here's a complete example of using IPCService to run benchmarks:

```bash
# Connect to the service (e.g., using netcat or socat)
nc -U /tmp/jvector.sock

# 1. Configure the benchmark
BENCH_CONFIG --output /data/results/benchmark --diag 1

# Expected response: OK

# 2. Add datasets to benchmark
BENCH_ADD_DATASET cap-1M dpr-1M cohere-english-v3-1M

# Expected response: OK

# 3. Start the benchmark
BENCH_START

# Expected response: OK

# 4. Check status (can be called multiple times)
BENCH_STATUS

# Expected response: RESULT RUNNING 1/3

# 5. Check status again after some time
BENCH_STATUS

# Expected response: RESULT RUNNING 2/3

# 6. Wait for completion
BENCH_STATUS

# Expected response: RESULT COMPLETED 3/3

# 7. Retrieve results
BENCH_RESULTS

# Expected response: RESULT { ... JSON data ... }
```

### Checkpoint Support

IPCService uses the same checkpoint mechanism as JvectorBench:
- Automatically saves progress after each dataset completes
- Resumes from checkpoint if benchmark is restarted
- Skips already-completed datasets

To restart a failed benchmark:
1. Reconnect to IPCService
2. Call `BENCH_CONFIG` with the same `--output` path
3. Call `BENCH_ADD_DATASET` with the same datasets
4. Call `BENCH_START` - it will automatically skip completed datasets

### Using with Python

Here's a Python example for using IPCService:

```python
import socket
import json

# Connect to socket
sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
sock.connect('/tmp/jvector.sock')

def send_command(cmd):
    """Send a command and return the response."""
    sock.sendall((cmd + '\n').encode('utf-8'))
    response = sock.recv(4096).decode('utf-8').strip()
    return response

# Configure benchmark
send_command('BENCH_CONFIG --output /data/results/benchmark')

# Add datasets
send_command('BENCH_ADD_DATASET cap-1M dpr-1M')

# Start benchmark
send_command('BENCH_START')

# Poll for completion
import time
while True:
    status = send_command('BENCH_STATUS')
    print(f"Status: {status}")

    if 'COMPLETED' in status or 'FAILED' in status:
        break

    time.sleep(30)  # Check every 30 seconds

# Get results
results_response = send_command('BENCH_RESULTS')
results_json = results_response.replace('RESULT ', '', 1)
results = json.loads(results_json)

print(f"Benchmark complete. Total results: {len(results)}")

sock.close()
```

---

## Dataset Management

Both JvectorBench and IPCService rely on datasets being available through the `DataSetLoader`. Datasets are referenced by name and should be configured in your environment.

Common dataset names:
- `cap-1M` - CLIP dataset with 1M vectors
- `cap-6M` - CLIP dataset with 6M vectors
- `cohere-english-v3-1M` - Cohere English v3 with 1M vectors
- `cohere-english-v3-10M` - Cohere English v3 with 10M vectors
- `dpr-1M` - DPR dataset with 1M vectors
- `dpr-10M` - DPR dataset with 10M vectors

You can use any dataset name that your `DataSetLoader` can resolve.

---

## Configuration Files

Both tools support custom YAML configuration files via `--config`. These files allow you to customize:
- Graph construction parameters (M, efConstruction, neighborOverflow, etc.)
- Search parameters (topK, pruning settings, etc.)
- Compression settings (PQ parameters, etc.)
- Feature sets to test

If no config is specified, the default "autoDefault" configuration is used.

---

## Troubleshooting

### JvectorBench

**Issue:** "Error: --output argument is required"
- **Solution:** Make sure you include `--output <path>` in your command

**Issue:** "Error: At least one dataset name must be provided"
- **Solution:** Add one or more dataset names after the flags

**Issue:** Benchmark interrupted
- **Solution:** Re-run the same command. It will resume from the checkpoint file

### IPCService

**Issue:** "Must call BENCH_CONFIG first"
- **Solution:** Always call `BENCH_CONFIG` before other benchmark commands

**Issue:** "Must add at least one dataset with BENCH_ADD_DATASET first"
- **Solution:** Call `BENCH_ADD_DATASET` with at least one dataset before calling `BENCH_START`

**Issue:** "Benchmark is already running"
- **Solution:** Wait for the current benchmark to complete or check status with `BENCH_STATUS`

**Issue:** Connection refused
- **Solution:** Make sure the IPCService is running and you're connecting to the correct socket path

---

## Performance Considerations

- **Memory:** Ensure adequate heap space for large datasets. Use `-Xmx` to set maximum heap size
- **Checkpoints:** Checkpoint files allow resuming from failures but add I/O overhead
- **Parallelism:** Benchmarks run sequentially per dataset but may use parallel search internally
- **Disk Space:** Output files (CSV, JSON, checkpoint) can be large for extensive benchmarks

Example with increased heap:
```bash
java -Xmx32g -jar jvector-bench.jar --output results/large cap-6M cohere-english-v3-10M
```