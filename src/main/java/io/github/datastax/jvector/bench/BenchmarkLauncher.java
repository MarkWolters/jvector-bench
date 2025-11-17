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

package io.github.datastax.jvector.bench;

import io.github.datastax.jvector.bench.cassandra.CassandraBenchmarkRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;

/**
 * Main launcher that routes to the appropriate benchmark runner based on the first argument.
 * This allows a single JAR to support multiple benchmark modes.
 * 
 * <p>Usage:
 * <pre>
 * # Run JVector benchmarks
 * java -jar jvector-bench.jar jvector --config config.yml --output results dataset1 dataset2
 * 
 * # Run Cassandra benchmarks
 * java -jar jvector-bench.jar cassandra load --connection conn.yml --dataset cap-1M --index-config idx.yml
 * java -jar jvector-bench.jar cassandra benchmark --connection conn.yml --dataset cap-1M --output results
 * java -jar jvector-bench.jar cassandra compare --jvector jvec.json --cassandra cass.json --output report.html
 * </pre>
 */
public class BenchmarkLauncher {
    private static final Logger logger = LoggerFactory.getLogger(BenchmarkLauncher.class);

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            printUsage();
            System.exit(1);
        }

        String command = args[0].toLowerCase();
        String[] commandArgs = Arrays.copyOfRange(args, 1, args.length);

        switch (command) {
            case "jvector":
                logger.info("Launching JVector benchmark runner");
                JvectorBench.main(commandArgs);
                break;
            
            case "cassandra":
                logger.info("Launching Cassandra benchmark runner");
                CassandraBenchmarkRunner.main(commandArgs);
                break;
            
            case "--help":
            case "-h":
            case "help":
                printUsage();
                break;
            
            default:
                // For backward compatibility, if the first argument looks like a JvectorBench argument,
                // route to JvectorBench with all arguments
                if (command.startsWith("--") || !command.contains("-")) {
                    logger.info("No command specified, assuming JVector benchmark mode for backward compatibility");
                    JvectorBench.main(args);
                } else {
                    logger.error("Unknown command: {}", command);
                    printUsage();
                    System.exit(1);
                }
        }
    }

    private static void printUsage() {
        System.out.println("JVector Benchmark Suite Launcher");
        System.out.println();
        System.out.println("Usage: java -jar jvector-bench.jar <command> [options]");
        System.out.println();
        System.out.println("Commands:");
        System.out.println("  jvector     Run JVector direct benchmarks");
        System.out.println("  cassandra   Run Cassandra integration benchmarks");
        System.out.println("  help        Show this help message");
        System.out.println();
        System.out.println("=== JVector Benchmark Mode ===");
        System.out.println("Usage: java -jar jvector-bench.jar jvector --output <path> [--config <path>] [--diag <level>] <dataset1> [dataset2] ...");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  --output <path>   Output path for results (required)");
        System.out.println("  --config <path>   Path to YAML configuration file (optional)");
        System.out.println("  --diag <level>    Diagnostic level (0-3, optional)");
        System.out.println("  <dataset>         One or more dataset names to benchmark");
        System.out.println();
        System.out.println("Example:");
        System.out.println("  java -jar jvector-bench.jar jvector --output results/benchmark --config config.yml siftsmall");
        System.out.println();
        System.out.println("=== Cassandra Benchmark Mode ===");
        System.out.println("Usage: java -jar jvector-bench.jar cassandra <subcommand> [options]");
        System.out.println();
        System.out.println("Subcommands:");
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
        System.out.println("  java -jar jvector-bench.jar cassandra load --connection conn.yml --dataset cap-1M --index-config idx.yml");
        System.out.println("  java -jar jvector-bench.jar cassandra benchmark --connection conn.yml --dataset cap-1M --output results/cap-1M");
        System.out.println("  java -jar jvector-bench.jar cassandra compare --jvector jvec.json --cassandra cass.json --output report.html");
        System.out.println();
        System.out.println("=== Backward Compatibility ===");
        System.out.println("For backward compatibility, if no command is specified and the first argument starts with '--',");
        System.out.println("the launcher will assume JVector benchmark mode:");
        System.out.println("  java -jar jvector-bench.jar --output results siftsmall");
        System.out.println("  (equivalent to: java -jar jvector-bench.jar jvector --output results siftsmall)");
    }
}

// Made with Bob
