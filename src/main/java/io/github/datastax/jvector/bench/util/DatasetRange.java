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

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Represents a dataset name with an optional range specification.
 * Supports parsing dataset names in the format: datasetName(start..end)
 * 
 * Examples:
 * - "cohere-english-v3-10M" -> full dataset
 * - "cohere-english-v3-10M(0..999999)" -> first 1M records
 * - "cohere-english-v3-10M(1000000..1999999)" -> second 1M records
 */
public class DatasetRange {
    private static final Pattern RANGE_PATTERN = Pattern.compile("^(.+)\\((\\d+)\\.\\.(\\d+)\\)$");
    
    private final String datasetName;
    private final Long startOrdinal;
    private final Long endOrdinal;
    
    /**
     * Parse a dataset specification string.
     * 
     * @param spec Dataset specification (e.g., "dataset-name" or "dataset-name(0..999)")
     * @return DatasetRange object
     * @throws IllegalArgumentException if the format is invalid
     */
    public static DatasetRange parse(String spec) {
        if (spec == null || spec.trim().isEmpty()) {
            throw new IllegalArgumentException("Dataset specification cannot be null or empty");
        }
        
        Matcher matcher = RANGE_PATTERN.matcher(spec);
        if (matcher.matches()) {
            String name = matcher.group(1);
            long start = Long.parseLong(matcher.group(2));
            long end = Long.parseLong(matcher.group(3));
            
            if (start < 0) {
                throw new IllegalArgumentException("Start ordinal cannot be negative: " + start);
            }
            if (end < start) {
                throw new IllegalArgumentException(
                    String.format("End ordinal (%d) must be >= start ordinal (%d)", end, start));
            }
            
            return new DatasetRange(name, start, end);
        } else {
            // No range specified, load entire dataset
            return new DatasetRange(spec, null, null);
        }
    }
    
    private DatasetRange(String datasetName, Long startOrdinal, Long endOrdinal) {
        this.datasetName = datasetName;
        this.startOrdinal = startOrdinal;
        this.endOrdinal = endOrdinal;
    }
    
    /**
     * Get the dataset name (without range specification).
     */
    public String getDatasetName() {
        return datasetName;
    }
    
    /**
     * Get the start ordinal (inclusive), or null if no range specified.
     */
    public Long getStartOrdinal() {
        return startOrdinal;
    }
    
    /**
     * Get the end ordinal (inclusive), or null if no range specified.
     */
    public Long getEndOrdinal() {
        return endOrdinal;
    }
    
    /**
     * Check if a range is specified.
     */
    public boolean hasRange() {
        return startOrdinal != null && endOrdinal != null;
    }
    
    /**
     * Get the number of vectors in the range (inclusive).
     * Returns null if no range is specified.
     */
    public Long getRangeSize() {
        if (!hasRange()) {
            return null;
        }
        return endOrdinal - startOrdinal + 1;
    }
    
    /**
     * Check if the given ordinal is within the specified range.
     * If no range is specified, always returns true.
     */
    public boolean isInRange(long ordinal) {
        if (!hasRange()) {
            return true;
        }
        return ordinal >= startOrdinal && ordinal <= endOrdinal;
    }
    
    @Override
    public String toString() {
        if (hasRange()) {
            return String.format("%s(%d..%d)", datasetName, startOrdinal, endOrdinal);
        }
        return datasetName;
    }
}

// Made with Bob
