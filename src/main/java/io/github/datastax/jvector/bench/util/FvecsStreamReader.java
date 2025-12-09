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

import io.github.jbellis.jvector.vector.VectorizationProvider;
import io.github.jbellis.jvector.vector.types.VectorFloat;
import io.github.jbellis.jvector.vector.types.VectorTypeSupport;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Streaming reader for .fvecs format files.
 * Reads vectors one at a time without loading entire dataset into memory.
 */
public class FvecsStreamReader implements VectorStreamReader {
    private static final VectorTypeSupport vectorTypeSupport = VectorizationProvider.getInstance().getVectorTypeSupport();
    
    private DataInputStream dis;
    private final int dimension;
    private final long totalVectors;
    private long position;
    private boolean hasNextCached;
    private boolean hasNextValue;
    
    /**
     * Create a streaming reader for an fvecs file.
     * @param filePath path to the .fvecs file
     */
    public FvecsStreamReader(String filePath) throws IOException {
        Path path = Paths.get(filePath);
        if (!Files.exists(path)) {
            throw new IOException("File not found: " + filePath);
        }
        
        this.position = 0;
        this.hasNextCached = false;
        
        // Read first dimension to determine vector size
        DataInputStream tempDis = new DataInputStream(new BufferedInputStream(new FileInputStream(filePath)));
        if (tempDis.available() > 0) {
            this.dimension = Integer.reverseBytes(tempDis.readInt());
            tempDis.close();
            
            // Calculate total vectors
            long fileSize = Files.size(path);
            long bytesPerVector = 4 + (dimension * 4L); // 4 bytes for dimension + dimension * 4 bytes for floats
            this.totalVectors = fileSize / bytesPerVector;
            
            // Open stream for reading
            this.dis = new DataInputStream(new BufferedInputStream(new FileInputStream(filePath), 1024 * 1024));
        } else {
            tempDis.close();
            throw new IOException("Empty file: " + filePath);
        }
    }
    
    @Override
    public boolean hasNext() throws IOException {
        if (!hasNextCached) {
            hasNextValue = dis.available() > 0;
            hasNextCached = true;
        }
        return hasNextValue;
    }
    
    @Override
    public VectorFloat<?> next() throws IOException {
        if (!hasNext()) {
            return null;
        }
        
        hasNextCached = false;
        
        // Read dimension (should match expected)
        int dim = Integer.reverseBytes(dis.readInt());
        if (dim != dimension) {
            throw new IOException(String.format("Dimension mismatch at vector %d: expected %d, got %d", 
                position, dimension, dim));
        }
        
        // Read vector data
        byte[] buffer = new byte[dimension * Float.BYTES];
        dis.readFully(buffer);
        ByteBuffer byteBuffer = ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN);
        
        float[] vector = new float[dimension];
        byteBuffer.asFloatBuffer().get(vector);
        
        position++;
        return vectorTypeSupport.createFloatVector(vector);
    }
    
    @Override
    public int getDimension() {
        return dimension;
    }
    
    @Override
    public long getTotalVectors() {
        return totalVectors;
    }
    
    @Override
    public long getPosition() {
        return position;
    }
    
    @Override
    public void close() throws IOException {
        if (dis != null) {
            dis.close();
        }
    }
}

// Made with Bob
