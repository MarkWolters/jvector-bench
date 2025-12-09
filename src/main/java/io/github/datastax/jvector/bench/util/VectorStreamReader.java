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

import io.github.jbellis.jvector.vector.types.VectorFloat;

import java.io.Closeable;
import java.io.IOException;

/**
 * Interface for streaming vectors from a dataset file without loading all into memory.
 * Allows processing large datasets that don't fit in memory.
 */
public interface VectorStreamReader extends Closeable {
    /**
     * Check if there are more vectors to read.
     * @return true if more vectors are available
     */
    boolean hasNext() throws IOException;
    
    /**
     * Read the next vector from the stream.
     * @return the next vector, or null if no more vectors
     */
    VectorFloat<?> next() throws IOException;
    
    /**
     * Get the dimension of vectors in this stream.
     * @return vector dimension
     */
    int getDimension();
    
    /**
     * Get the total number of vectors in the stream (if known).
     * @return total vector count, or -1 if unknown
     */
    long getTotalVectors();
    
    /**
     * Get the current position (number of vectors read so far).
     * @return current position
     */
    long getPosition();
}

// Made with Bob
