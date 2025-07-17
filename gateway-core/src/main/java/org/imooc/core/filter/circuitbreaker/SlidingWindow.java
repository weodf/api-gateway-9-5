package org.imooc.core.filter.circuitbreaker;

import lombok.Builder;
import lombok.Data;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 滑动窗口统计实现
 * 用于统计指定时间窗口内的请求成功率和响应时间
 */
public class SlidingWindow {

    private final long windowSizeMs;
    private final int bucketCount;
    private final long bucketSizeMs;
    private final Bucket[] buckets;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    public SlidingWindow(long windowSizeMs, int bucketCount) {
        this.windowSizeMs = windowSizeMs;
        this.bucketCount = bucketCount;
        this.bucketSizeMs = windowSizeMs / bucketCount;
        this.buckets = new Bucket[bucketCount];

        for (int i = 0; i < bucketCount; i++) {
            buckets[i] = new Bucket();
        }
    }

    /**
     * 添加样本数据
     */
    public void addSample(boolean success, long responseTimeMs) {
        lock.writeLock().lock();
        try {
            long currentTime = System.currentTimeMillis();
            int bucketIndex = getBucketIndex(currentTime);
            Bucket bucket = buckets[bucketIndex];

            // 如果bucket太旧，重置它
            if (currentTime - bucket.getTimestamp() > windowSizeMs) {
                bucket.reset(currentTime);
            }

            bucket.addSample(success, responseTimeMs);

        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * 获取窗口统计信息
     */
    public WindowStats getStats() {
        lock.readLock().lock();
        try {
            long currentTime = System.currentTimeMillis();
            int totalRequests = 0;
            int failureCount = 0;
            long totalResponseTime = 0;

            for (Bucket bucket : buckets) {
                // 只统计窗口时间内的bucket
                if (currentTime - bucket.getTimestamp() <= windowSizeMs) {
                    totalRequests += bucket.getTotalRequests();
                    failureCount += bucket.getFailureCount();
                    totalResponseTime += bucket.getTotalResponseTime();
                }
            }

            double failureRate = totalRequests > 0 ? (double) failureCount / totalRequests * 100 : 0;
            double averageResponseTime = totalRequests > 0 ? (double) totalResponseTime / totalRequests : 0;

            return WindowStats.builder()
                    .totalRequests(totalRequests)
                    .failureCount(failureCount)
                    .successCount(totalRequests - failureCount)
                    .failureRate(failureRate)
                    .averageResponseTime(averageResponseTime)
                    .build();

        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * 重置窗口
     */
    public void reset() {
        lock.writeLock().lock();
        try {
            for (Bucket bucket : buckets) {
                bucket.reset(System.currentTimeMillis());
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    private int getBucketIndex(long timestamp) {
        return (int) ((timestamp / bucketSizeMs) % bucketCount);
    }

    /**
     * 单个bucket统计
     */
    private static class Bucket {
        private final AtomicLong timestamp = new AtomicLong(0);
        private final AtomicInteger totalRequests = new AtomicInteger(0);
        private final AtomicInteger failureCount = new AtomicInteger(0);
        private final AtomicLong totalResponseTime = new AtomicLong(0);

        public void addSample(boolean success, long responseTimeMs) {
            totalRequests.incrementAndGet();
            if (!success) {
                failureCount.incrementAndGet();
            }
            totalResponseTime.addAndGet(responseTimeMs);
        }

        public void reset(long newTimestamp) {
            timestamp.set(newTimestamp);
            totalRequests.set(0);
            failureCount.set(0);
            totalResponseTime.set(0);
        }

        public long getTimestamp() {
            return timestamp.get();
        }

        public int getTotalRequests() {
            return totalRequests.get();
        }

        public int getFailureCount() {
            return failureCount.get();
        }

        public long getTotalResponseTime() {
            return totalResponseTime.get();
        }
    }

    /**
     * 窗口统计结果
     */
    @Data
    @Builder
    public static class WindowStats {
        private int totalRequests;
        private int failureCount;
        private int successCount;
        private double failureRate;
        private double averageResponseTime;

        public double getSuccessRate() {
            return 100.0 - failureRate;
        }
    }
}
