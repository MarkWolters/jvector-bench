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

package io.github.datastax.jvector.bench.util;

import java.io.IOException;

public class DataSetLoader {
    public static DataSet loadDataSet(String fileName) throws IOException {
        DataSet ds;
        if (fileName.endsWith(".hdf5")) {
            DownloadHelper.maybeDownloadHdf5(fileName);
            ds = Hdf5Loader.load(fileName);
        } else {
            var mfd = DownloadHelper.maybeDownloadFvecs(fileName);
            ds = mfd.load();
        }
        return ds;
    }
    
    /**
     * Load only query vectors and ground truth from a dataset, without loading base vectors.
     * This is memory-efficient for benchmarking against external indexes (like Cassandra)
     * where the base vectors are already loaded.
     *
     * @param fileName Dataset name
     * @return DataSet with minimal base vectors (just one dummy vector)
     * @throws IOException if files cannot be read
     * @throws UnsupportedOperationException if the dataset format doesn't support query-only loading
     */
    public static DataSet loadQueriesOnly(String fileName) throws IOException {
        return loadQueriesOnly(fileName, null);
    }
    
    /**
     * Load only query vectors and ground truth from a dataset, without loading base vectors.
     * This is memory-efficient for benchmarking against external indexes (like Cassandra)
     * where the base vectors are already loaded.
     *
     * @param fileName Dataset name
     * @param groundTruthPath Optional path to ground truth file (overrides default if provided)
     * @return DataSet with minimal base vectors (just one dummy vector)
     * @throws IOException if files cannot be read
     * @throws UnsupportedOperationException if the dataset format doesn't support query-only loading
     */
    public static DataSet loadQueriesOnly(String fileName, String groundTruthPath) throws IOException {
        if (fileName.endsWith(".hdf5")) {
            throw new UnsupportedOperationException(
                "Query-only loading is not yet supported for HDF5 datasets. " +
                "Use loadDataSet() instead or convert to fvecs format.");
        } else {
            var mfd = DownloadHelper.maybeDownloadFvecs(fileName);
            return mfd.loadQueriesOnly(groundTruthPath);
        }
    }
}
