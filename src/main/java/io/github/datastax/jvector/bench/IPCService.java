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

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.datastax.jvector.bench.util.BenchmarkSummarizer;
import io.github.datastax.jvector.bench.util.CheckpointManager;
import io.github.datastax.jvector.bench.util.DataSet;
import io.github.datastax.jvector.bench.util.DataSetLoader;
import io.github.datastax.jvector.bench.util.MMapRandomAccessVectorValues;
import io.github.datastax.jvector.bench.util.UpdatableRandomAccessVectorValues;
import io.github.datastax.jvector.bench.yaml.MultiConfig;
import io.github.jbellis.jvector.graph.*;
import io.github.jbellis.jvector.graph.disk.OnDiskGraphIndex;
import io.github.jbellis.jvector.graph.similarity.DefaultSearchScoreProvider;
import io.github.jbellis.jvector.graph.similarity.ScoreFunction;
import io.github.jbellis.jvector.quantization.CompressedVectors;
import io.github.jbellis.jvector.quantization.ProductQuantization;
import io.github.jbellis.jvector.util.Bits;
import io.github.jbellis.jvector.vector.VectorSimilarityFunction;
import io.github.jbellis.jvector.vector.VectorizationProvider;
import io.github.jbellis.jvector.vector.types.VectorFloat;
import io.github.jbellis.jvector.vector.types.VectorTypeSupport;
import org.newsclub.net.unix.AFUNIXServerSocket;
import org.newsclub.net.unix.AFUNIXSocket;
import org.newsclub.net.unix.AFUNIXSocketAddress;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ForkJoinPool;
import java.util.stream.IntStream;

/**
 * Simple local service to use for interaction with JVector over IPC.
 * Only handles a single connection at a time.
 */
public class IPCService
{
    private static final Logger logger = LoggerFactory.getLogger(IPCService.class);
    private static final VectorTypeSupport vectorTypeSupport = VectorizationProvider.getInstance().getVectorTypeSupport();
    private static final ObjectMapper objectMapper = new ObjectMapper();

    // How each command message is marked as finished
    private static final String DELIM = "\n";

    static class SessionContext {
        // Vector indexing state
        boolean isBulkLoad = false;

        int dimension;
        int M;
        int efConstruction;
        float neighborOverflow;
        boolean addHierarchy;
        boolean refineFinalGraph;
        VectorSimilarityFunction similarityFunction;
        RandomAccessVectorValues ravv;
        CompressedVectors cv;
        GraphIndexBuilder indexBuilder;
        ImmutableGraphIndex index;
        GraphSearcher searcher;
        final StringBuffer result = new StringBuffer(1024);

        // Benchmark state
        String benchConfigPath;
        MultiConfig benchConfig;
        String benchOutputPath;
        List<String> benchDatasetPatterns = new ArrayList<>();
        List<BenchResult> benchResults = new ArrayList<>();
        CompletableFuture<Void> benchmarkFuture;
        CheckpointManager checkpointManager;
        volatile String benchmarkStatus = "NOT_STARTED";
        volatile String benchmarkError = null;
        int diagnosticLevel = 0;
    }

    enum Command {
        // Vector indexing commands
        CREATE,  //DIMENSIONS SIMILARITY_TYPE M EF\n
        WRITE,  //[N,N,N] [N,N,N]...\n
        BULKLOAD, // /path/to/local/file
        OPTIMIZE, //Run once finished writing
        SEARCH, //EF-search limit [N,N,N] [N,N,N]...\n
        MEMORY, // Memory usage in kb

        // Benchmark commands
        BENCH_CONFIG,  // [--config /path/to/config] [--output /path/to/output] [--diag level]
        BENCH_ADD_DATASET,  // dataset1 dataset2 ... (dataset names)
        BENCH_START,  // Start running benchmarks
        BENCH_STATUS,  // Get current status
        BENCH_RESULTS,  // Get results in JSON format
    }

    enum Response {
        OK,
        ERROR,
        RESULT
    }

    final Path socketFile;
    final AFUNIXServerSocket unixSocket;
    IPCService(Path socketFile) throws IOException {
        this.socketFile = socketFile;
        this.unixSocket = AFUNIXServerSocket.newInstance();
        this.unixSocket.bind(AFUNIXSocketAddress.of(socketFile));
    }

    void create(String input, SessionContext ctx) {
        String[] args = input.split("\\s+");

//        if (args.length != 6)
//            throw new IllegalArgumentException("Illegal CREATE statement. Expecting 'CREATE [DIMENSIONS] [SIMILARITY_TYPE] [M] [EF]'");

        int dimensions = Integer.parseInt(args[0]);
        VectorSimilarityFunction sim = VectorSimilarityFunction.valueOf(args[1]);
        int M = Integer.parseInt(args[2]);
        int efConstruction = Integer.parseInt(args[3]);
        float neighborOverflow = Float.parseFloat(args[4]);
        boolean addHierarchy = Boolean.parseBoolean(args[5]);
        boolean refineFinalGraph = Boolean.parseBoolean(args[6]);

        ctx.ravv = new UpdatableRandomAccessVectorValues(dimensions);
        ctx.indexBuilder =  new GraphIndexBuilder(ctx.ravv, sim, M, efConstruction, neighborOverflow, 1.4f, addHierarchy, refineFinalGraph);
        ctx.M = M;
        ctx.dimension = dimensions;
        ctx.efConstruction = efConstruction;
        ctx.similarityFunction = sim;
        ctx.isBulkLoad = false;
        ctx.addHierarchy = addHierarchy;
        ctx.refineFinalGraph = refineFinalGraph;
    }

    void write(String input, SessionContext ctx) {
        if (ctx.isBulkLoad)
            throw new IllegalStateException("Session is for bulk loading.  To reset call CREATE again");

        String[] args = input.split("\\s+");
        for (int i = 0; i < args.length; i++) {
            String vStr = args[i];
            if (!vStr.startsWith("[") || !vStr.endsWith("]"))
                throw new IllegalArgumentException("Invalid vector encountered. Expecting 'WRITE [F1,F2...] [F3,F4...] ...' but got " + vStr);

            String[] values = vStr.substring(1, vStr.length() - 1).split(",");
            if (values.length != ctx.ravv.dimension())
                throw new IllegalArgumentException(String.format("Invalid vector dimension: %d %d!=%d", i, values.length, ctx.ravv.dimension()));

            VectorFloat<?> vector = vectorTypeSupport.createFloatVector(ctx.dimension);
            for (int k = 0; k < vector.length(); k++)
                vector.set(k, Float.parseFloat(values[k]));

            ((UpdatableRandomAccessVectorValues)ctx.ravv).add(vector);
            var node = ctx.ravv.size() - 1;
            ctx.indexBuilder.addGraphNode(node, ctx.ravv.getVector(node));
        }
    }

    void bulkLoad(String input, SessionContext ctx) {
        String[] args = input.split("\\s+");
        if (args.length != 1)
            throw new IllegalArgumentException("Invalid arguments. Expecting 'BULKLOAD /path/to/local/file'. got " + input);

        File f = new File(args[0]);
        if (!f.exists())
            throw new IllegalArgumentException("No file at: " + f);

        long length = f.length();
        if (length % ((long) ctx.dimension * Float.BYTES) != 0)
            throw new IllegalArgumentException("File is not encoded correctly");

        ctx.index = null;
        ctx.ravv = null;
        ctx.indexBuilder = null;
        ctx.isBulkLoad = true;

        var ravv = new MMapRandomAccessVectorValues(f, ctx.dimension);
        var indexBuilder = new GraphIndexBuilder(ravv, ctx.similarityFunction, ctx.M, ctx.efConstruction, ctx.neighborOverflow, 1.4f, ctx.addHierarchy, ctx.refineFinalGraph);
        System.out.println("BulkIndexing " + ravv.size());
        ctx.index = flushGraphIndex(indexBuilder.build(ravv), ravv);
        ctx.cv = pqIndex(ravv, ctx);

        //Finished with raw data we can close/cleanup
        ravv.close();
        ctx.searcher = new GraphSearcher(ctx.index);
    }

    private CompressedVectors pqIndex(RandomAccessVectorValues ravv, SessionContext ctx) {
        var pqDims = ctx.dimension > 10 ? Math.max(ctx.dimension / 4, 10) : ctx.dimension;
        long start = System.nanoTime();
        ProductQuantization pq = ProductQuantization.compute(ravv, pqDims, 256, ctx.similarityFunction == VectorSimilarityFunction.EUCLIDEAN);
        System.out.format("PQ@%s build %.2fs,%n", pqDims, (System.nanoTime() - start) / 1_000_000_000.0);
        start = System.nanoTime();
        var cv = pq.encodeAll(ravv);
        System.out.format("PQ encoded %d vectors [%.2f MB] in %.2fs,%n", ravv.size(), (cv.ramBytesUsed()/1024f/1024f) , (System.nanoTime() - start) / 1_000_000_000.0);
        return cv;
    }

    private static ImmutableGraphIndex flushGraphIndex(ImmutableGraphIndex index, RandomAccessVectorValues ravv) {
        try {
            var testDirectory = Files.createTempDirectory("BenchGraphDir");
            var graphPath = testDirectory.resolve("graph.bin");

            OnDiskGraphIndex.write(index, ravv, graphPath);
            return index;
        } catch (IOException e) {
            throw new IOError(e);
        }
    }

    void optimize(SessionContext ctx) {
        if (!ctx.isBulkLoad) {
            if (ctx.ravv.size() > 256) {
                ctx.indexBuilder.cleanup();
                ctx.index = flushGraphIndex(ctx.indexBuilder.getGraph(), ctx.ravv);
                ctx.cv = pqIndex(ctx.ravv, ctx);
                ctx.indexBuilder = null;
                ctx.ravv = null;
            } else { //Not enough data for PQ
                ctx.indexBuilder.cleanup();
                ctx.index = ctx.indexBuilder.getGraph();
                ctx.cv = null;
            }

        }
    }

    String memory(SessionContext ctx) {
        long kb = 0;
        if (ctx.cv != null)
            kb = ctx.cv.ramBytesUsed() / 1024;
        return String.format("%s %d\n", Response.RESULT, kb);
    }

    void benchConfig(String input, SessionContext ctx) {
        String[] args = input.split("\\s+");

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--config":
                    if (i + 1 < args.length) {
                        ctx.benchConfigPath = args[++i];
                    }
                    break;
                case "--output":
                    if (i + 1 < args.length) {
                        ctx.benchOutputPath = args[++i];
                        ctx.checkpointManager = new CheckpointManager(ctx.benchOutputPath);
                        logger.info("Initialized checkpoint manager. Already completed datasets: {}",
                                    ctx.checkpointManager.getCompletedDatasets());
                    }
                    break;
                case "--diag":
                    if (i + 1 < args.length) {
                        ctx.diagnosticLevel = Integer.parseInt(args[++i]);
                        Grid.setDiagnosticLevel(ctx.diagnosticLevel);
                    }
                    break;
            }
        }

        if (ctx.benchOutputPath == null) {
            throw new IllegalArgumentException("--output is required for benchmark configuration");
        }

        logger.info("Benchmark configured: output={}, config={}, diag={}",
                    ctx.benchOutputPath, ctx.benchConfigPath, ctx.diagnosticLevel);
    }

    void benchAddDataset(String input, SessionContext ctx) {
        if (ctx.benchOutputPath == null) {
            throw new IllegalStateException("Must call BENCH_CONFIG first");
        }

        String[] datasetNames = input.split("\\s+");
        for (String datasetName : datasetNames) {
            if (!datasetName.trim().isEmpty()) {
                ctx.benchDatasetPatterns.add(datasetName.trim());
            }
        }

        logger.info("Added datasets: {}", Arrays.toString(datasetNames));
    }

    void benchStart(SessionContext ctx) {
        if (ctx.benchOutputPath == null) {
            throw new IllegalStateException("Must call BENCH_CONFIG first");
        }

        if (ctx.benchDatasetPatterns.isEmpty()) {
            throw new IllegalStateException("Must add at least one dataset with BENCH_ADD_DATASET first");
        }

        if (ctx.benchmarkFuture != null && !ctx.benchmarkFuture.isDone()) {
            throw new IllegalStateException("Benchmark is already running");
        }

        // Reset results and status
        ctx.benchResults.clear();
        ctx.benchmarkStatus = "RUNNING";
        ctx.benchmarkError = null;

        // Load any existing results from checkpoint
        if (ctx.checkpointManager != null) {
            ctx.benchResults.addAll(ctx.checkpointManager.getCompletedResults());
        }

        // Start benchmark in background thread
        ctx.benchmarkFuture = CompletableFuture.runAsync(() -> {
            try {
                logger.info("Starting benchmark execution");

                // Use dataset names directly from benchDatasetPatterns
                List<String> datasetNames = new ArrayList<>(ctx.benchDatasetPatterns);

                logger.info("Executing the following datasets: {}", datasetNames);

                // Process each dataset
                for (String datasetName : datasetNames) {
                    // Skip already completed datasets
                    if (ctx.checkpointManager != null && ctx.checkpointManager.isDatasetCompleted(datasetName)) {
                        logger.info("Skipping already completed dataset: {}", datasetName);
                        continue;
                    }

                    logger.info("Loading dataset: {}", datasetName);
                    DataSet ds = DataSetLoader.loadDataSet(datasetName);
                    logger.info("Dataset loaded: {} with {} vectors", datasetName, ds.baseVectors.size());

                    String normalizedDatasetName = datasetName;
                    if (normalizedDatasetName.endsWith(".hdf5")) {
                        normalizedDatasetName = normalizedDatasetName.substring(0, normalizedDatasetName.length() - ".hdf5".length());
                    }

                    MultiConfig config;
                    if (ctx.benchConfigPath != null) {
                        config = MultiConfig.getConfig(ctx.benchConfigPath);
                        if (config.dataset == null || config.dataset.isEmpty()) {
                            config.dataset = normalizedDatasetName;
                        }
                    } else {
                        config = MultiConfig.getDefaultConfig("autoDefault");
                        config.dataset = normalizedDatasetName;
                    }
                    logger.info("Using configuration: {}", config);

                    List<BenchResult> datasetResults = Grid.runAllAndCollectResults(ds,
                            config.construction.outDegree,
                            config.construction.efConstruction,
                            config.construction.neighborOverflow,
                            config.construction.addHierarchy,
                            config.construction.getFeatureSets(),
                            config.construction.getCompressorParameters(),
                            config.search.getCompressorParameters(),
                            config.search.topKOverquery,
                            config.search.useSearchPruning);

                    synchronized (ctx.benchResults) {
                        ctx.benchResults.addAll(datasetResults);
                    }

                    logger.info("Benchmark completed for dataset: {}", datasetName);

                    // Mark dataset as completed and update checkpoint
                    if (ctx.checkpointManager != null) {
                        ctx.checkpointManager.markDatasetCompleted(datasetName, datasetResults);
                    }
                }

                // Write final results
                writeBenchmarkResults(ctx);

                ctx.benchmarkStatus = "COMPLETED";
                logger.info("Benchmark execution completed successfully");

            } catch (Exception e) {
                logger.error("Benchmark execution failed", e);
                ctx.benchmarkStatus = "FAILED";
                ctx.benchmarkError = e.getMessage();
            }
        });

        logger.info("Benchmark started in background");
    }

    private void writeBenchmarkResults(SessionContext ctx) throws IOException {
        // Write results to JSON file
        File detailsFile = new File(ctx.benchOutputPath + ".json");
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(detailsFile, ctx.benchResults);

        // Calculate summary statistics
        BenchmarkSummarizer.SummaryStats stats = BenchmarkSummarizer.summarize(ctx.benchResults);
        logger.info("Benchmark summary: {}", stats);

        // Write CSV file
        File outputFile = new File(ctx.benchOutputPath + ".csv");
        Map<String, BenchmarkSummarizer.SummaryStats> statsByDataset =
            BenchmarkSummarizer.summarizeByDataset(ctx.benchResults);

        try (FileWriter writer = new FileWriter(outputFile)) {
            writer.write("dataset,QPS,QPS StdDev,Mean Latency,Recall@10,Index Construction Time\n");

            for (Map.Entry<String, BenchmarkSummarizer.SummaryStats> entry : statsByDataset.entrySet()) {
                String dataset = entry.getKey();
                BenchmarkSummarizer.SummaryStats datasetStats = entry.getValue();

                writer.write(dataset + ",");
                writer.write(datasetStats.getAvgQps() + ",");
                writer.write(datasetStats.getQpsStdDev() + ",");
                writer.write(datasetStats.getAvgLatency() + ",");
                writer.write(datasetStats.getAvgRecall() + ",");
                writer.write(datasetStats.getIndexConstruction() + "\n");
            }
        }

        logger.info("Benchmark results written to {} and {}", outputFile, detailsFile);
    }

    String benchStatus(SessionContext ctx) {
        if (ctx.benchOutputPath == null) {
            return String.format("%s NOT_CONFIGURED\n", Response.RESULT);
        }

        String status = ctx.benchmarkStatus;
        int completedDatasets = ctx.checkpointManager != null ?
            ctx.checkpointManager.getCompletedDatasets().size() : 0;
        int totalDatasets = ctx.benchDatasetPatterns.size();

        String errorInfo = ctx.benchmarkError != null ? " ERROR: " + ctx.benchmarkError : "";

        return String.format("%s %s %d/%d%s\n", Response.RESULT, status, completedDatasets, totalDatasets, errorInfo);
    }

    String benchResults(SessionContext ctx) throws IOException {
        if (ctx.benchResults.isEmpty()) {
            return String.format("%s []\n", Response.RESULT);
        }

        String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(ctx.benchResults);
        // Replace newlines with spaces to keep response on one line
        json = json.replace("\n", " ").replace("\r", "");
        return String.format("%s %s\n", Response.RESULT, json);
    }

    String search(String input, SessionContext ctx) {
        String[] args = input.split("\\s+");

        if (args.length < 3)
            throw new IllegalArgumentException("Invalid arguments search-ef top-k [vector1] [vector2]...");

        int searchEf = Integer.parseInt(args[0]);
        int topK = Integer.parseInt(args[1]);

        VectorFloat<?> queryVector = vectorTypeSupport.createFloatVector(ctx.dimension);
        ctx.result.setLength(0);
        ctx.result.append(Response.RESULT);
        int[][] results = new int[args.length - 2][];
        IntStream loopStream = IntStream.range(0, args.length-2);

        //Only use parallel path if we have > 1 core
        if (ForkJoinPool.commonPool().getPoolSize() > 1)
            loopStream.parallel();

        loopStream.forEach(i -> {
            String vStr = args[i + 2]; //Skipping first 2 args which are not vectors
            if (!vStr.startsWith("[") || !vStr.endsWith("]"))
                throw new IllegalArgumentException("Invalid query vector encountered:" + vStr);

            String[] values = vStr.substring(1, vStr.length() - 1).split(",");
            if (values.length != ctx.dimension)
                throw new IllegalArgumentException(String.format("Invalid vector dimension: %d!=%d", values.length, ctx.dimension));


            for (int k = 0; k < queryVector.length(); k++)
                queryVector.set(k, Float.parseFloat(values[k]));

            SearchResult r;
            if (ctx.cv != null) {
                ScoreFunction.ApproximateScoreFunction sf = ctx.cv.precomputedScoreFunctionFor(queryVector, ctx.similarityFunction);
                try (var view = ctx.index.getView()) {
                    var rr = view instanceof ImmutableGraphIndex.ScoringView
                            ? ((ImmutableGraphIndex.ScoringView) view).rerankerFor(queryVector, ctx.similarityFunction)
                            : ctx.ravv.rerankerFor(queryVector, ctx.similarityFunction);
                    var ssp = new DefaultSearchScoreProvider(sf, rr);
                    r = new GraphSearcher(ctx.index).search(ssp, searchEf, Bits.ALL);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            } else {
                r = GraphSearcher.search(queryVector, topK, ctx.ravv, ctx.similarityFunction, ctx.index, Bits.ALL);
            }

            var resultNodes = r.getNodes();
            int count = Math.min(resultNodes.length, topK);

            results[i] = new int[count];
            for (int k = 0; k < count; k++)
                results[i][k] = resultNodes[k].node;
        });

        //Format Response
        for (int[] result : results) {
            ctx.result.append(" [");
            for (int k = 0; k < result.length; k++) {
                if (k > 0) ctx.result.append(",");
                ctx.result.append(result[k]);
            }
            ctx.result.append("]");
        }
        ctx.result.append("\n");
        return ctx.result.toString();
    }

    String process(String input, SessionContext ctx) throws IOException {
        int delim = input.indexOf(' ');
        String command = delim < 1 ? input : input.substring(0, delim);
        String commandArgs = delim > 0 ? input.substring(delim + 1) : "";
        String response = Response.OK.name() + "\n";
        switch (Command.valueOf(command)) {
            // Vector indexing commands
            case CREATE: create(commandArgs, ctx); break;
            case WRITE: write(commandArgs, ctx); break;
            case BULKLOAD: bulkLoad(commandArgs, ctx); break;
            case OPTIMIZE: optimize(ctx); break;
            case SEARCH: response = search(commandArgs, ctx); break;
            case MEMORY: response = memory(ctx); break;

            // Benchmark commands
            case BENCH_CONFIG: benchConfig(commandArgs, ctx); break;
            case BENCH_ADD_DATASET: benchAddDataset(commandArgs, ctx); break;
            case BENCH_START: benchStart(ctx); break;
            case BENCH_STATUS: response = benchStatus(ctx); break;
            case BENCH_RESULTS: response = benchResults(ctx); break;

            default: throw new UnsupportedOperationException("No support for: '" + command + "'");
        }
        return response;
    }

    void serve() throws IOException {
        int bufferSize = unixSocket.getReceiveBufferSize();
        byte[] buffer = new byte[bufferSize];
        StringBuffer sb = new StringBuffer(1024);
        System.out.println("Service listening on " + socketFile);
        while (true) {
            AFUNIXSocket connection = unixSocket.accept();
            System.out.println("new connection!");
            SessionContext context = new SessionContext();

            try (InputStream is = connection.getInputStream();
                 OutputStream os = connection.getOutputStream()) {
                int read;
                while ((read = is.read(buffer)) != -1) {
                    String s = new String(buffer, 0, read, StandardCharsets.UTF_8);
                    if (s.contains(DELIM)) {
                        int doffset;
                        while ((doffset = s.indexOf(DELIM)) != -1) {
                            sb.append(s, 0, doffset);

                            //Save tail for next loop
                            s = s.substring(doffset + 1);
                            try {
                                String cmd = sb.toString();
                                if (!cmd.trim().isEmpty()) {
                                    String response = process(cmd, context);
                                    os.write(response.getBytes(StandardCharsets.UTF_8));
                                }
                            } catch (Throwable t) {
                                String response = String.format("%s %s\n", Response.ERROR, t.getMessage());
                                os.write(response.getBytes(StandardCharsets.UTF_8));
                                t.printStackTrace();
                            }

                            //Reset buffer
                            sb.setLength(0);
                        }
                    } else {
                        sb.append(s);
                    }
                }
            }
        }
    }

    static void help() {
        System.out.println("Usage: ipcservice.jar [/unix/socket/path.sock]");
        System.exit(1);
    }

    public static void main(String[] args) {
        String socketFile = System.getProperty("java.io.tmpdir") + "/jvector.sock";
        if (args.length > 1)
            help();

        if (args.length == 1)
            socketFile = args[0];

        try {
            IPCService service = new IPCService(Path.of(socketFile));
            service.serve();
        } catch (Throwable t) {
            t.printStackTrace();
            System.exit(1);
        }

        System.exit(0);
    }
}
