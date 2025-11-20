/*
 * Copyright DataStax, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.github.datastax.jvector.bench.cassandra;

import com.datastax.oss.driver.api.core.cql.BatchStatement;
import com.datastax.oss.driver.api.core.cql.BatchStatementBuilder;
import com.datastax.oss.driver.api.core.cql.BatchType;
import com.datastax.oss.driver.api.core.cql.PreparedStatement;
import com.datastax.oss.driver.api.core.cql.SimpleStatement;
import com.datastax.oss.driver.api.core.data.CqlVector;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.datastax.jvector.bench.BenchResult;
import io.github.datastax.jvector.bench.benchmarks.Metric;
import io.github.datastax.jvector.bench.cassandra.benchmarks.*;
import io.github.datastax.jvector.bench.cassandra.comparison.*;
import io.github.datastax.jvector.bench.cassandra.config.*;
import io.github.datastax.jvector.bench.util.BenchmarkSummarizer;
import io.github.datastax.jvector.bench.util.DataSet;
import io.github.datastax.jvector.bench.util.DataSetLoader;
import io.github.jbellis.jvector.vector.types.VectorFloat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Main entry point for Cassandra benchmarks.
 * Supports three commands: load, benchmark, compare.
 */
public class CassandraBenchmarkRunner {
    private static final Logger logger = LoggerFactory.getLogger(CassandraBenchmarkRunner.class);

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            printUsage();
            System.exit(1);
        }

        String command = args[0];
        String[] commandArgs = Arrays.copyOfRange(args, 1, args.length);

        switch (command) {
            case "load":
                runLoad(commandArgs);
                break;
            case "benchmark":
                runBenchmark(commandArgs);
                break;
            case "compare":
                runCompare(commandArgs);
                break;
            default:
                logger.error("Unknown command: {}", command);
                printUsage();
                System.exit(1);
        }
    }

    private static void printUsage() {
        System.out.println("Cassandra Benchmark Runner");
        System.out.println();
        System.out.println("Usage: cassandra-bench <command> [options]");
        System.out.println();
        System.out.println("Commands:");
        System.out.println("  load       Load a dataset into Cassandra");
        System.out.println("  benchmark  Run benchmarks against loaded dataset");
        System.out.println("  compare    Compare jvector-direct vs Cassandra results");
        System.out.println();
        System.out.println("Load Options:");
        System.out.println("  --connection <path>    Path to Cassandra connection config YAML (required)");
        System.out.println("  --dataset <name>       Dataset name to load (required)");
        System.out.println("  --index-config <path>  Path to vector index config YAML (required)");
        System.out.println("  --batch-size <n>       Batch size for inserts (default: 500)");
        System.out.println("  --concurrency <n>      Concurrent batch operations (default: 32)");
        System.out.println("  --drop-existing        Drop existing table/index before loading");
        System.out.println("  --skip-index-wait      Don't wait for index build to complete");
        System.out.println();
        System.out.println("Benchmark Options:");
        System.out.println("  --connection <path>    Path to Cassandra connection config YAML (required)");
        System.out.println("  --dataset <name>       Dataset name (must match loaded data) (required)");
        System.out.println("  --output <path>        Output path for results (required)");
        System.out.println("  --topK <n>             Number of results to return (default: 10)");
        System.out.println("  --query-runs <n>       Number of times to run queries (default: 2)");
        System.out.println();
        System.out.println("Compare Options:");
        System.out.println("  --jvector <path>       Path to jvector-bench results JSON (required)");
        System.out.println("  --cassandra <path>     Path to cassandra-bench results JSON (required)");
        System.out.println("  --output <path>        Output path for comparison report (required)");
        System.out.println();
        System.out.println("Examples:");
        System.out.println("  cassandra-bench load --connection conn.yml --dataset cap-1M --index-config idx.yml");
        System.out.println("  cassandra-bench benchmark --connection conn.yml --dataset cap-1M --output results/cap-1M");
        System.out.println("  cassandra-bench compare --jvector jvec.json --cassandra cass.json --output report.html");
    }

    /**
     * Load dataset into Cassandra.
     */
    private static void runLoad(String[] args) throws Exception {
        Map<String, String> params = parseArgs(args);

        String connectionPath = params.get("connection");
        String datasetName = params.get("dataset");
        String indexConfigPath = params.get("index-config");

        if (connectionPath == null || datasetName == null || indexConfigPath == null) {
            logger.error("Missing required arguments for load command");
            printUsage();
            System.exit(1);
        }

        // Parse configuration
        CassandraConfig cassConfig = CassandraConfig.fromYaml(connectionPath);
        VectorIndexConfig indexConfig = VectorIndexConfig.fromYaml(indexConfigPath);

        // Parse loading options
        LoadingConfig loadConfig = new LoadingConfig();
        if (params.containsKey("batch-size")) {
            loadConfig.setBatchSize(Integer.parseInt(params.get("batch-size")));
        }
        if (params.containsKey("concurrency")) {
            loadConfig.setConcurrency(Integer.parseInt(params.get("concurrency")));
        }
        loadConfig.setDropExisting(params.containsKey("drop-existing"));
        loadConfig.setSkipIndexWait(params.containsKey("skip-index-wait"));

        logger.info("Loading dataset {} into Cassandra", datasetName);
        logger.info("Connection config: {}", connectionPath);
        logger.info("Index config: {}", indexConfig);
        logger.info("Loading config: {}", loadConfig);

        // Load dataset
        logger.info("Loading dataset from disk...");
        DataSet ds = DataSetLoader.loadDataSet(datasetName);
        logger.info("Dataset loaded: {} vectors, dimension {}", ds.baseVectors.size(), ds.getDimension());

        // Validate dimension matches
        if (ds.getDimension() != indexConfig.getDimension()) {
            throw new IllegalArgumentException(String.format(
                "Dataset dimension (%d) doesn't match index config dimension (%d)",
                ds.getDimension(), indexConfig.getDimension()
            ));
        }

        // Connect to Cassandra
        try (CassandraConnection connection = CassandraConnection.connect(cassConfig)) {
            // Setup schema
            connection.ensureSchema(indexConfig, loadConfig.isDropExisting());

            // Load data
            loadDataIntoIndex(connection, ds, loadConfig);

            logger.info("Dataset loading complete!");
        }
    }

    /**
     * Load dataset vectors into Cassandra.
     */
    private static void loadDataIntoIndex(CassandraConnection connection, DataSet ds, LoadingConfig loadConfig)
        throws Exception {

        logger.info("Loading {} vectors into Cassandra...", ds.baseVectors.size());

        PreparedStatement insert = connection.getSession().prepare(
            String.format("INSERT INTO %s.%s (id, vector) VALUES (?, ?)",
                connection.getConfig().getKeyspace(),
                connection.getConfig().getTable())
        );

        int batchSize = loadConfig.getBatchSize();
        int concurrency = loadConfig.getConcurrency();
        Semaphore semaphore = new Semaphore(concurrency);
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger errorCount = new AtomicInteger(0);

        long startTime = System.nanoTime();
        int totalVectors = ds.baseVectors.size();
        int totalBatches = (totalVectors + batchSize - 1) / batchSize;

        logger.info("Loading {} vectors in {} batches (batch size: {}, concurrency: {})",
            totalVectors, totalBatches, batchSize, concurrency);

        for (int i = 0; i < totalVectors; i += batchSize) {
            BatchStatementBuilder batch = BatchStatement.builder(
                loadConfig.isLogged() ? BatchType.LOGGED : BatchType.UNLOGGED
            );

            // Build batch
            int batchEnd = Math.min(i + batchSize, totalVectors);
            for (int j = i; j < batchEnd; j++) {
                VectorFloat<?> vector = ds.baseVectors.get(j);
                List<Float> vectorList = vectorToList(vector);
                batch.addStatement(insert.bind(String.valueOf(j), CqlVector.newInstance(vectorList)));
            }

            // Execute batch asynchronously with rate limiting
            final int batchStartIdx = i;
            final int batchEndIdx = batchEnd;
            semaphore.acquire();
            CompletableFuture<Void> future = connection.getSession()
                .executeAsync(batch.build())
                .toCompletableFuture()
                .handle((rs, throwable) -> {
                    try {
                        if (throwable != null) {
                            logger.error("Failed to load batch [{}-{}): {}",
                                batchStartIdx, batchEndIdx, throwable.getMessage());
                            errorCount.incrementAndGet();
                        } else {
                            int completed = successCount.incrementAndGet();
                            if (completed % 10 == 0 || completed == totalBatches) {
                                int vectorsLoaded = Math.min(completed * batchSize, totalVectors);
                                logger.info("Progress: {} / {} batches ({} / {} vectors, {:.1f}%)",
                                    completed, totalBatches, vectorsLoaded, totalVectors,
                                    (vectorsLoaded * 100.0) / totalVectors);
                            }
                        }
                    } finally {
                        semaphore.release();
                    }
                    return null;
                });

            futures.add(future);
        }

        // Wait for all batches
        logger.info("Waiting for all batches to complete...");
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        double elapsed = (System.nanoTime() - startTime) / 1e9;
        logger.info("Data loading finished in {:.2f} seconds ({:.0f} vectors/sec)",
            elapsed, totalVectors / elapsed);
        logger.info("Success: {} batches, Errors: {} batches", successCount.get(), errorCount.get());

        // verify the index is built
        connection.waitForIndexBuild();
        double endTime = (System.nanoTime() - startTime) / 1e9;
        connection.writeIndexBuildTime(ds.name, endTime);

        // Wait for Cassandra to flush writes before validating
        logger.info("Waiting for Cassandra to flush writes...");
        try {
            Thread.sleep(5000);  // 5 second delay for Cassandra to flush and make data visible
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while waiting for flush", e);
        }

        // Validate actual row count in Cassandra
        logger.info("Validating loaded data (COUNT query may take some time)...");
        String countQuery = String.format("SELECT COUNT(*) FROM %s.vectors", connection.getConfig().getKeyspace());
        SimpleStatement statement = SimpleStatement.builder(countQuery)
            .setTimeout(Duration.ofSeconds(120))  // COUNT(*) can be slow on large tables
            .build();
        long actualCount = connection.getSession().execute(statement).one().getLong(0);
        logger.info("Actual rows in Cassandra: {} (expected: {})", actualCount, totalVectors);

        if (actualCount != totalVectors) {
            throw new RuntimeException(String.format(
                "Data validation failed: expected %d vectors but found %d in Cassandra",
                totalVectors, actualCount));
        }

        if (errorCount.get() > 0) {
            logger.warn("Data loaded successfully despite {} batch errors (likely transient/retried)",
                errorCount.get());
        }

        logger.info("Data loading validation successful!");
    }

    /**
     * Run benchmarks against loaded dataset.
     */
    private static void runBenchmark(String[] args) throws Exception {
        Map<String, String> params = parseArgs(args);

        String connectionPath = params.get("connection");
        String datasetName = params.get("dataset");
        String outputPath = params.get("output");
        String indexConfigPath = params.get("index-config");

        if (connectionPath == null || datasetName == null || outputPath == null || indexConfigPath == null) {
            logger.error("Missing required arguments for benchmark command");
            printUsage();
            System.exit(1);
        }

        VectorIndexConfig indexConfig = VectorIndexConfig.fromYaml(indexConfigPath);

        int topK = Integer.parseInt(params.getOrDefault("topK", "10"));
        int queryRuns = Integer.parseInt(params.getOrDefault("query-runs", "2"));

        // Parse configuration
        CassandraConfig cassConfig = CassandraConfig.fromYaml(connectionPath);

        logger.info("Running benchmarks against Cassandra");
        logger.info("Dataset: {}", datasetName);
        logger.info("TopK: {}, Query runs: {}", topK, queryRuns);

        // Load dataset (for queries)
        DataSet ds = DataSetLoader.loadDataSet(datasetName);
        logger.info("Loaded {} query vectors", ds.queryVectors.size());

        // Connect and run benchmarks
        try (CassandraConnection connection = CassandraConnection.connect(cassConfig)) {
            // Create search configuration
            // Use search config from index config if available, otherwise use defaults
            SearchConfig searchConfig;
            if (indexConfig.getSearchConfig() != null) {
                searchConfig = indexConfig.getSearchConfig();
                // Override similarity function to match index if not set
                if (searchConfig.getSimilarityFunction() == null) {
                    searchConfig.setSimilarityFunction(indexConfig.getSimilarityFunction());
                }
            } else {
                searchConfig = new SearchConfig();
                searchConfig.setSimilarityFunction(indexConfig.getSimilarityFunction());
            }
            searchConfig.setReadConsistency(cassConfig.getReadConsistencyLevel());

            // Create benchmark instances
            List<CassandraBenchmark> benchmarks = new ArrayList<>();
            benchmarks.add(CassandraThroughputBenchmark.createDefault());
            benchmarks.add(CassandraLatencyBenchmark.createDefault());
            benchmarks.add(CassandraAccuracyBenchmark.createDefault());

            // Collect all metrics
            List<BenchResult> results = new ArrayList<>();

            // Run each benchmark
            for (CassandraBenchmark benchmark : benchmarks) {
                logger.info("Running {}...", benchmark.getBenchmarkName());

                List<Metric> metrics = benchmark.runBenchmark(
                    connection,
                    ds,
                    topK,
                    searchConfig,
                    queryRuns
                );

                // Convert metrics to BenchResult format
                Map<String, Object> metricParams = new HashMap<>();
                metricParams.put("dataset", ds.name);
                metricParams.put("topK", topK);
                metricParams.put("queryRuns", queryRuns);
                metricParams.put("benchmark", benchmark.getBenchmarkName());

                for (Metric metric : metrics) {
                    Map<String, Object> metricMap = new HashMap<>();
                    metricMap.put(metric.getHeader(), metric.getValue());
                    results.add(new BenchResult(ds.name, metricParams, metricMap));
                }

                logger.info("{} complete", benchmark.getBenchmarkName());
            }
            results.add(new BenchResult(ds.name, VectorIndexConfig.getParams(),
                    Map.of("Index Build Time", connection.getIndexBuildTime(ds.name))));

            // Write results
            writeResults(results, outputPath, ds.name);
        }
    }

    /**
     * Compare jvector-direct vs Cassandra results.
     */
    private static void runCompare(String[] args) throws Exception {
        Map<String, String> params = parseArgs(args);

        String jvectorPath = params.get("jvector");
        String cassandraPath = params.get("cassandra");
        String outputPath = params.get("output");
        String format = params.getOrDefault("format", "html");
        double threshold = Double.parseDouble(params.getOrDefault("threshold", "10.0"));

        if (jvectorPath == null || cassandraPath == null || outputPath == null) {
            logger.error("Missing required arguments for compare command");
            printUsage();
            System.exit(1);
        }

        logger.info("Comparing benchmark results");
        logger.info("JVector results: {}", jvectorPath);
        logger.info("Cassandra results: {}", cassandraPath);
        logger.info("Output: {}", outputPath);
        logger.info("Format: {}, Threshold: {}%", format, threshold);

        // Load and compare results
        ComparisonReport report = ComparisonReport.fromFiles(jvectorPath, cassandraPath, threshold);
        Map<String, ComparisonResult> comparisons = report.compare();

        // Analyze overhead for each dataset
        Map<String, OverheadAnalyzer.OverheadAnalysis> analyses = new HashMap<>();
        for (Map.Entry<String, ComparisonResult> entry : comparisons.entrySet()) {
            OverheadAnalyzer.OverheadAnalysis analysis = OverheadAnalyzer.analyze(entry.getValue());
            analyses.put(entry.getKey(), analysis);
        }

        // Generate report in requested format
        ReportFormatter formatter = getFormatter(format);
        String reportContent = formatter.format(comparisons, analyses);

        // Write report to file
        String outputFile = outputPath;
        if (!outputPath.contains(".")) {
            outputFile = outputPath + "." + formatter.getFileExtension();
        }

        try (FileWriter writer = new FileWriter(outputFile)) {
            writer.write(reportContent);
        }

        logger.info("Comparison report written to {}", outputFile);

        // Print summary to console
        System.out.println("\n" + report.getSummary());

        // Print significant differences
        List<String> significant = report.getSignificantDifferences();
        if (!significant.isEmpty()) {
            System.out.println("\nSignificant Differences (threshold: " + threshold + "%):");
            for (String diff : significant) {
                System.out.println("  " + diff);
            }
        }
    }

    /**
     * Get report formatter for the specified format.
     */
    private static ReportFormatter getFormatter(String format) {
        return switch (format.toLowerCase()) {
            case "html" -> new HtmlReportFormatter();
            case "markdown", "md" -> new MarkdownReportFormatter();
            case "json" -> new JsonReportFormatter();
            default -> {
                logger.warn("Unknown format '{}', using HTML", format);
                yield new HtmlReportFormatter();
            }
        };
    }

    /**
     * Write benchmark results to files.
     */
    private static void writeResults(List<BenchResult> results, String outputPath, String datasetName)
        throws IOException {

        // Write JSON
        File jsonFile = new File(outputPath + ".json");
        ObjectMapper mapper = new ObjectMapper();
        mapper.writerWithDefaultPrettyPrinter().writeValue(jsonFile, results);
        logger.info("Results written to {}", jsonFile);

        // Write CSV
        File csvFile = new File(outputPath + ".csv");
        Map<String, BenchmarkSummarizer.SummaryStats> statsByDataset =
            BenchmarkSummarizer.summarizeByDataset(results);

        try (FileWriter writer = new FileWriter(csvFile)) {
            writer.write("dataset,QPS,QPS StdDev,Mean Latency,Recall@10,Index Construction Time\n");

            for (Map.Entry<String, BenchmarkSummarizer.SummaryStats> entry : statsByDataset.entrySet()) {
                BenchmarkSummarizer.SummaryStats stats = entry.getValue();
                writer.write(String.format("%s,%.1f,%.1f,%.3f,%.4f,%.2f\n",
                    datasetName,
                    stats.getAvgQps(),
                    stats.getQpsStdDev(),
                    stats.getAvgLatency(),
                    stats.getAvgRecall(),
                    stats.getIndexConstruction()
                ));
            }
        }

        logger.info("CSV written to {}", csvFile);
    }

    /**
     * Convert VectorFloat to List<Float>.
     */
    private static List<Float> vectorToList(VectorFloat<?> vector) {
        List<Float> list = new ArrayList<>(vector.length());
        for (int i = 0; i < vector.length(); i++) {
            list.add(vector.get(i));
        }
        return list;
    }

    /**
     * Parse command-line arguments into a map.
     */
    private static Map<String, String> parseArgs(String[] args) {
        Map<String, String> params = new HashMap<>();

        for (int i = 0; i < args.length; i++) {
            if (args[i].startsWith("--")) {
                String key = args[i].substring(2);

                // Check if this is a flag (no value)
                if (i + 1 >= args.length || args[i + 1].startsWith("--")) {
                    params.put(key, "true");
                } else {
                    params.put(key, args[i + 1]);
                    i++; // Skip the value
                }
            }
        }

        return params;
    }
}
