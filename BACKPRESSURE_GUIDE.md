# Backpressure Mechanism for Cassandra Data Loading

## Overview

The CassandraBenchmarkRunner now includes a dynamic backpressure mechanism that automatically adjusts the data loading rate when the Cassandra cluster becomes overwhelmed. This prevents batch write timeouts and CPU saturation on small clusters.

## How It Works

The backpressure controller monitors the error rate of batch operations and dynamically adjusts two parameters:

1. **Concurrency Level**: The number of concurrent batch operations allowed
2. **Delay Between Batches**: An optional delay added between batch submissions

### Automatic Adjustment

- **When errors increase** (error rate exceeds threshold):
  - Reduces concurrency by 50%
  - Doubles the delay between batches (up to max)
  - Logs a warning with the new settings

- **When things stabilize** (50+ consecutive successes):
  - Increases concurrency by 20% (up to max)
  - Halves the delay between batches (down to min)
  - Logs an info message about recovery

### Key Features

- **Sliding Window**: Monitors recent operations (default: last 100 batches)
- **Error Threshold**: Triggers backoff when error rate exceeds 10% (configurable)
- **Minimum Adjustment Interval**: Prevents thrashing by limiting adjustments to once per 5 seconds
- **Gradual Recovery**: Slowly increases throughput as cluster recovers

## Configuration

Backpressure is **enabled by default** with sensible defaults. You can customize it via command-line arguments or YAML configuration.

### Command-Line Arguments

When using the `load` command, you can disable or configure backpressure:

```bash
# Disable backpressure (use fixed concurrency)
cassandra-bench load --connection conn.yml --dataset cap-1M --index-config idx.yml \
  --disable-backpressure

# Customize backpressure parameters
cassandra-bench load --connection conn.yml --dataset cap-1M --index-config idx.yml \
  --backpressure-error-threshold 0.15 \
  --backpressure-window-size 200 \
  --backpressure-min-delay-ms 100 \
  --backpressure-max-delay-ms 10000
```

### YAML Configuration

You can also configure backpressure in your index configuration YAML file:

```yaml
loading:
  batch_size: 500
  concurrency: 32
  enable_backpressure: true
  backpressure_error_threshold: 0.1    # 10% error rate triggers backoff
  backpressure_window_size: 100        # Monitor last 100 batches
  backpressure_min_delay_ms: 0         # No minimum delay
  backpressure_max_delay_ms: 5000      # Max 5 second delay between batches
```

### Configuration Parameters

| Parameter | Default | Description |
|-----------|---------|-------------|
| `enable_backpressure` | `true` | Enable/disable the backpressure mechanism |
| `backpressure_error_threshold` | `0.1` | Error rate (0.0-1.0) that triggers backoff |
| `backpressure_window_size` | `100` | Number of recent operations to monitor |
| `backpressure_min_delay_ms` | `0` | Minimum delay between batches (milliseconds) |
| `backpressure_max_delay_ms` | `5000` | Maximum delay between batches (milliseconds) |

## Monitoring

The backpressure controller provides detailed logging:

### Initialization
```
INFO  BackpressureController initialized: maxConcurrency=32, errorThreshold=0.1, windowSize=100
INFO  Backpressure enabled: errorThreshold=0.1, windowSize=100, minDelay=0ms, maxDelay=5000ms
```

### Progress Updates (every 10 batches)
```
INFO  Progress: 50 / 200 batches (25000 / 100000 vectors, 25.0%) - Concurrency: 32/32, Delay: 0ms, Errors: 2/50 (4.0%)
```

### Backoff Triggered
```
WARN  BACKPRESSURE TRIGGERED: Error rate 12.5% - Reducing concurrency 32 -> 16, delay 0 -> 100ms
```

### Recovery
```
INFO  BACKPRESSURE RECOVERY: Increasing concurrency 16 -> 19, delay 100 -> 50ms
```

### Final Statistics
```
INFO  Final backpressure stats: Concurrency: 28/32, Delay: 0ms, Errors: 15/200 (7.5%)
```

## Use Cases

### Small Clusters
For small Cassandra clusters (1-3 nodes), use conservative settings:

```yaml
loading:
  batch_size: 250
  concurrency: 16
  enable_backpressure: true
  backpressure_error_threshold: 0.05   # More aggressive backoff
  backpressure_max_delay_ms: 10000     # Allow longer delays
```

### Large Clusters
For large clusters (10+ nodes), you can be more aggressive:

```yaml
loading:
  batch_size: 1000
  concurrency: 64
  enable_backpressure: true
  backpressure_error_threshold: 0.15   # Tolerate more errors
  backpressure_max_delay_ms: 2000      # Shorter max delay
```

### Disable for Testing
To test maximum throughput without backpressure:

```yaml
loading:
  batch_size: 500
  concurrency: 32
  enable_backpressure: false
```

## Implementation Details

### BackpressureController Class

The `BackpressureController` class manages the dynamic rate limiting:

- **Thread-safe**: Uses atomic operations for counters
- **Non-blocking**: Uses semaphores for concurrency control
- **Adaptive**: Adjusts based on real-time error rates
- **Configurable**: All parameters can be customized

### Integration Points

The backpressure controller is integrated into the `loadDataIntoIndex` method:

1. **Initialization**: Creates controller with configured parameters
2. **Batch Submission**: Acquires permit before submitting batch
3. **Success Handling**: Records success and checks for recovery
4. **Error Handling**: Records error and triggers backoff if needed
5. **Cleanup**: Releases permit after batch completes

## Troubleshooting

### Still Getting Timeouts

If you're still experiencing timeouts with backpressure enabled:

1. **Reduce initial concurrency**: Start with lower concurrency (e.g., 8-16)
2. **Lower error threshold**: Use 0.05 instead of 0.1
3. **Add minimum delay**: Set `backpressure_min_delay_ms: 100`
4. **Reduce batch size**: Use smaller batches (e.g., 250 instead of 500)

### Too Slow

If loading is too slow:

1. **Increase error threshold**: Use 0.15 or 0.2
2. **Reduce window size**: Use 50 instead of 100 for faster response
3. **Increase max concurrency**: Start with higher concurrency
4. **Disable backpressure**: If cluster can handle it

### Monitoring Cluster Health

Watch your Cassandra cluster metrics during loading:

- **CPU Usage**: Should stay below 80-90%
- **Write Latency**: Should remain stable
- **Pending Compactions**: Should not grow unbounded
- **Dropped Mutations**: Should remain at zero

## Best Practices

1. **Start Conservative**: Begin with lower concurrency and let backpressure increase it
2. **Monitor Logs**: Watch for backpressure triggers and adjust thresholds
3. **Test First**: Run a small test load to find optimal settings
4. **Cluster-Specific**: Tune parameters based on your cluster size and hardware
5. **Leave Enabled**: Keep backpressure enabled for production loads

## Example Scenarios

### Scenario 1: Loading 1M vectors to 3-node cluster

```bash
cassandra-bench load \
  --connection conn.yml \
  --dataset cap-1M \
  --index-config idx.yml \
  --batch-size 250 \
  --concurrency 16
```

Expected behavior:
- Starts with 16 concurrent batches
- If errors occur, reduces to 8, then 4 if needed
- Adds delays up to 5 seconds between batches
- Gradually recovers as cluster catches up

### Scenario 2: Loading 10M vectors to 10-node cluster

```bash
cassandra-bench load \
  --connection conn.yml \
  --dataset cap-10M \
  --index-config idx.yml \
  --batch-size 1000 \
  --concurrency 64 \
  --backpressure-error-threshold 0.15
```

Expected behavior:
- Starts with 64 concurrent batches
- Tolerates up to 15% error rate before backing off
- Can handle higher throughput with larger cluster
- Minimal delays needed

## Performance Impact

The backpressure mechanism has minimal overhead:

- **CPU**: Negligible (atomic operations only)
- **Memory**: ~1KB per controller instance
- **Latency**: Adds configured delay only when needed
- **Throughput**: Optimizes for sustainable throughput vs. peak

## Future Enhancements

Potential improvements for future versions:

- Adaptive window size based on cluster size
- Integration with Cassandra metrics (CPU, latency)
- Per-node backpressure tracking
- Predictive backoff based on trends
- Configuration profiles for common cluster sizes