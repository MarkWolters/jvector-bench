# Cassandra Query Parameters Configuration

This document describes the query-time parameters that can be configured for Cassandra vector searches in jvector-bench.

## Overview

Cassandra exposes two key parameters for controlling ANN (Approximate Nearest Neighbor) search behavior at query time:

1. **rerank_k** - Controls the number of candidates to rerank during search
2. **use_pruning** - Enables or disables pruning during graph traversal

These parameters are now configurable through the vector index configuration files.

## Configuration

### Location

Query parameters are configured in the `search_config` section of vector index configuration files located in:
```
src/main/resources/cassandra-configs/vector-index-*.yml
```

### Format

```yaml
vector_index:
  dimension: 1536
  similarity_function: "DOT_PRODUCT"
  maximum_node_connections: 16
  construction_beam_width: 100
  source_model: "ADA002"
  enable_hierarchy: false
  
  # Optional search-time parameters
  search_config:
    # Number of candidates to rerank during ANN search
    rerank_k: 100
    
    # Enable/disable pruning during search
    use_pruning: true
```

## Parameters

### rerank_k

**Type:** Integer (optional)

**Description:** Controls the number of candidates to retrieve and rerank during ANN search. This is the Cassandra equivalent of the `efSearch` parameter in HNSW.

**Behavior:**
- If not specified: Cassandra uses a default value based on the query limit and compression type
- If set to 0 or negative: Uses the query limit value and skips reranking (rerankless search)
- If set to a positive value: Must be >= query limit

**Impact:**
- Higher values → Better recall, slower queries
- Lower values → Faster queries, potentially lower recall

**Example values:**
- `rerank_k: 100` - Good balance for most use cases
- `rerank_k: 200` - Higher recall for critical queries
- `rerank_k: 0` - Fastest queries, skip reranking (for compressed indexes)

**Guardrails:**
Cassandra has configurable guardrails for rerank_k:
- Default warning threshold: -1 (disabled)
- Default failure threshold: 4 × max_top_k (typically 4000)

### use_pruning

**Type:** Boolean (optional)

**Description:** Controls whether pruning is enabled during graph traversal.

**Behavior:**
- If not specified: Defaults to `true`
- `true`: Enables pruning (default behavior)
- `false`: Disables pruning

**Impact:**
- Pruning enabled: Better performance, may slightly reduce recall
- Pruning disabled: Potentially better recall, slower queries

**Recommendation:** Leave at default (`true`) unless you have specific recall requirements.

## How It Works

When you configure these parameters in the YAML file:

1. **Loading:** The `VectorIndexConfig.fromYaml()` method reads the configuration including the nested `search_config` section
2. **Application:** The `CassandraBenchmarkRunner` extracts the search config and passes it to benchmarks
3. **Query Execution:** The `CassandraConnection.prepareSearch()` method builds CQL queries with the ANN options clause:
   ```sql
   SELECT ... ORDER BY vector ANN OF ? LIMIT ? 
   WITH ann_options = {'rerank_k': 100, 'use_pruning': true}
   ```

## Example Configurations

### High Recall Configuration
```yaml
search_config:
  rerank_k: 200
  use_pruning: false
```
Use when: Recall is critical, query latency is less important

### Balanced Configuration
```yaml
search_config:
  rerank_k: 100
  use_pruning: true
```
Use when: Good balance between recall and performance (recommended default)

### High Performance Configuration
```yaml
search_config:
  rerank_k: 50
  use_pruning: true
```
Use when: Query latency is critical, slight recall reduction is acceptable

### Rerankless Configuration (Compressed Indexes)
```yaml
search_config:
  rerank_k: 0
  use_pruning: true
```
Use when: Using compressed vectors, want fastest possible queries

### Default Configuration (Omit section)
```yaml
# No search_config section
```
Use when: Want Cassandra to use its automatic defaults based on model and compression

## Testing

To test different configurations:

1. Edit the appropriate config file in `src/main/resources/cassandra-configs/`
2. Run benchmarks with the modified config:
   ```bash
   cassandra-bench benchmark \
     --connection connection-local.yml \
     --dataset siftsmall \
     --index-config vector-index-siftsmall.yml \
     --output results/test
   ```
3. Compare results with different parameter values

## Code Changes Summary

The following files were modified to support these parameters:

1. **SearchConfig.java** - Added `rerankK` and `usePruning` fields with `buildAnnOptionsClause()` method
2. **VectorIndexConfig.java** - Added `searchConfig` field to hold search parameters
3. **CassandraConnection.java** - Modified `prepareSearch()` to include ANN options in CQL
4. **CassandraBenchmarkRunner.java** - Updated to use search config from index config
5. **All vector-index-*.yml files** - Added commented examples of search_config section

## References

- Cassandra ANN Options: See `org.apache.cassandra.db.filter.ANNOptions` in Cassandra source
- HNSW Parameters: The `rerank_k` parameter is analogous to `efSearch` in HNSW algorithms
- Guardrails: Configured in `cassandra.yaml` via `sai_ann_rerank_k_warn_threshold` and `sai_ann_rerank_k_fail_threshold`