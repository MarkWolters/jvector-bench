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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Dynamic backpressure controller that adjusts concurrency based on error rates.
 * When errors increase, it reduces concurrency and adds delays. When things stabilize,
 * it gradually increases concurrency back to the configured maximum.
 */
public class BackpressureController {
    private static final Logger logger = LoggerFactory.getLogger(BackpressureController.class);
    
    private final int maxConcurrency;
    private final double errorThreshold;
    private final int windowSize;
    private final long minDelayMs;
    private final long maxDelayMs;
    
    private final Semaphore semaphore;
    private final AtomicInteger currentConcurrency;
    private final AtomicInteger successCount;
    private final AtomicInteger errorCount;
    private final AtomicLong currentDelayMs;
    private final AtomicInteger consecutiveSuccesses;
    private final AtomicLong lastAdjustmentTime;
    
    // Adjustment parameters
    private static final int MIN_CONCURRENCY = 1;
    private static final double BACKOFF_FACTOR = 0.5;  // Reduce concurrency by 50% on errors
    private static final double RECOVERY_FACTOR = 1.2;  // Increase concurrency by 20% on recovery
    private static final int RECOVERY_THRESHOLD = 50;   // Consecutive successes needed to recover
    private static final long ADJUSTMENT_INTERVAL_MS = 5000;  // Min time between adjustments
    
    /**
     * Create a backpressure controller with default settings.
     * 
     * @param maxConcurrency Maximum concurrent operations allowed
     */
    public BackpressureController(int maxConcurrency) {
        this(maxConcurrency, 0.1, 100, 0, 5000);
    }
    
    /**
     * Create a backpressure controller with custom settings.
     * 
     * @param maxConcurrency Maximum concurrent operations allowed
     * @param errorThreshold Error rate threshold (0.0-1.0) that triggers backoff
     * @param windowSize Number of recent operations to consider for error rate
     * @param minDelayMs Minimum delay between batches in milliseconds
     * @param maxDelayMs Maximum delay between batches in milliseconds
     */
    public BackpressureController(int maxConcurrency, double errorThreshold, int windowSize, 
                                   long minDelayMs, long maxDelayMs) {
        this.maxConcurrency = maxConcurrency;
        this.errorThreshold = errorThreshold;
        this.windowSize = windowSize;
        this.minDelayMs = minDelayMs;
        this.maxDelayMs = maxDelayMs;
        
        this.semaphore = new Semaphore(maxConcurrency);
        this.currentConcurrency = new AtomicInteger(maxConcurrency);
        this.successCount = new AtomicInteger(0);
        this.errorCount = new AtomicInteger(0);
        this.currentDelayMs = new AtomicLong(minDelayMs);
        this.consecutiveSuccesses = new AtomicInteger(0);
        this.lastAdjustmentTime = new AtomicLong(System.currentTimeMillis());
        
        logger.info("BackpressureController initialized: maxConcurrency={}, errorThreshold={}, windowSize={}", 
                    maxConcurrency, errorThreshold, windowSize);
    }
    
    /**
     * Acquire a permit to send a batch. This may block if concurrency limit is reached.
     * Also applies any configured delay for rate limiting.
     */
    public void acquire() throws InterruptedException {
        semaphore.acquire();
        
        // Apply delay if configured
        long delay = currentDelayMs.get();
        if (delay > 0) {
            Thread.sleep(delay);
        }
    }
    
    /**
     * Release a permit after batch completion.
     */
    public void release() {
        semaphore.release();
    }
    
    /**
     * Record a successful batch operation.
     */
    public void recordSuccess() {
        successCount.incrementAndGet();
        int consecutive = consecutiveSuccesses.incrementAndGet();
        
        // Check if we should try to recover (increase concurrency)
        if (consecutive >= RECOVERY_THRESHOLD && canAdjust()) {
            tryRecover();
        }
        
        // Reset window if we've exceeded the window size
        checkAndResetWindow();
    }
    
    /**
     * Record a failed batch operation and potentially trigger backoff.
     */
    public void recordError() {
        errorCount.incrementAndGet();
        consecutiveSuccesses.set(0);  // Reset consecutive success counter
        
        // Check if error rate exceeds threshold
        int total = successCount.get() + errorCount.get();
        if (total >= windowSize) {
            double errorRate = (double) errorCount.get() / total;
            
            if (errorRate >= errorThreshold && canAdjust()) {
                triggerBackoff(errorRate);
            }
            
            checkAndResetWindow();
        }
    }
    
    /**
     * Trigger backoff by reducing concurrency and increasing delay.
     */
    private void triggerBackoff(double errorRate) {
        int current = currentConcurrency.get();
        int newConcurrency = Math.max(MIN_CONCURRENCY, (int) (current * BACKOFF_FACTOR));
        
        // Reduce available permits
        int permitsToReduce = current - newConcurrency;
        if (permitsToReduce > 0) {
            semaphore.acquireUninterruptibly(permitsToReduce);
            currentConcurrency.set(newConcurrency);
        }
        
        // Increase delay exponentially
        long currentDelay = currentDelayMs.get();
        long newDelay = Math.min(maxDelayMs, Math.max(minDelayMs, currentDelay * 2));
        if (newDelay == 0 && maxDelayMs > 0) {
            newDelay = 100;  // Start with 100ms if delay was 0
        }
        currentDelayMs.set(newDelay);
        
        lastAdjustmentTime.set(System.currentTimeMillis());
        
        logger.warn("BACKPRESSURE TRIGGERED: Error rate {}% - Reducing concurrency {} -> {}, delay {} -> {}ms",
                    errorRate * 100, current, newConcurrency, currentDelay, newDelay);
    }
    
    /**
     * Try to recover by increasing concurrency and reducing delay.
     */
    private void tryRecover() {
        int current = currentConcurrency.get();
        if (current >= maxConcurrency) {
            return;  // Already at max
        }
        
        int newConcurrency = Math.min(maxConcurrency, (int) (current * RECOVERY_FACTOR));
        
        // Add back permits
        int permitsToAdd = newConcurrency - current;
        if (permitsToAdd > 0) {
            semaphore.release(permitsToAdd);
            currentConcurrency.set(newConcurrency);
        }
        
        // Reduce delay
        long currentDelay = currentDelayMs.get();
        long newDelay = Math.max(minDelayMs, currentDelay / 2);
        currentDelayMs.set(newDelay);
        
        lastAdjustmentTime.set(System.currentTimeMillis());
        consecutiveSuccesses.set(0);  // Reset counter
        
        logger.info("BACKPRESSURE RECOVERY: Increasing concurrency {} -> {}, delay {} -> {}ms",
                    current, newConcurrency, currentDelay, newDelay);
    }
    
    /**
     * Check if enough time has passed since last adjustment.
     */
    private boolean canAdjust() {
        long now = System.currentTimeMillis();
        long lastAdjust = lastAdjustmentTime.get();
        return (now - lastAdjust) >= ADJUSTMENT_INTERVAL_MS;
    }
    
    /**
     * Reset the sliding window counters if we've exceeded the window size.
     */
    private void checkAndResetWindow() {
        int total = successCount.get() + errorCount.get();
        if (total >= windowSize * 2) {
            // Reset counters but keep proportions
            int errors = errorCount.get();
            int successes = successCount.get();
            double errorRate = (double) errors / total;
            
            errorCount.set((int) (windowSize * errorRate));
            successCount.set(windowSize - errorCount.get());
        }
    }
    
    /**
     * Get current concurrency level.
     */
    public int getCurrentConcurrency() {
        return currentConcurrency.get();
    }
    
    /**
     * Get current delay in milliseconds.
     */
    public long getCurrentDelayMs() {
        return currentDelayMs.get();
    }
    
    /**
     * Get current error rate.
     */
    public double getCurrentErrorRate() {
        int total = successCount.get() + errorCount.get();
        if (total == 0) {
            return 0.0;
        }
        return (double) errorCount.get() / total;
    }
    
    /**
     * Get statistics summary.
     */
    public String getStats() {
        int total = successCount.get() + errorCount.get();
        double errorRate = total > 0 ? (double) errorCount.get() / total : 0.0;
        
        return String.format("Concurrency: %d/%d, Delay: %dms, Errors: %d/%d (%.1f%%)",
                           currentConcurrency.get(), maxConcurrency,
                           currentDelayMs.get(),
                           errorCount.get(), total, errorRate * 100);
    }
}

// Made with Bob
