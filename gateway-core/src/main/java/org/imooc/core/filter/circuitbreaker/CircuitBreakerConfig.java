package org.imooc.core.filter.circuitbreaker;

import lombok.Data;

/**
 * 熔断器配置类
 */
@Data
public class CircuitBreakerConfig {

    // 基础配置
    private int failureThresholdPercentage = 50;  // 失败率阈值（百分比）
    private int minRequestThreshold = 20;         // 最小请求数阈值
    private long timeoutMs = 60000;               // 熔断超时时间（毫秒）

    // 半开状态配置
    private int halfOpenMaxRequests = 5;          // 半开状态最大请求数
    private int halfOpenSuccessThreshold = 3;     // 半开状态成功阈值

    // 滑动窗口配置
    private long windowSizeMs = 60000;            // 统计窗口大小（毫秒）
    private int bucketCount = 10;                 // 窗口内bucket数量

    // 降级配置
    private String fallbackResponse;              // 降级响应内容
    private boolean enableFallback = true;        // 是否启用降级

    // 监控配置
    private boolean enableMetrics = true;         // 是否启用指标收集
    private String metricsPrefix = "circuitbreaker"; // 指标前缀

    /**
     * 验证配置的有效性
     */
    public void validate() {
        if (failureThresholdPercentage < 1 || failureThresholdPercentage > 100) {
            throw new IllegalArgumentException("failureThresholdPercentage must be between 1 and 100");
        }

        if (minRequestThreshold < 1) {
            throw new IllegalArgumentException("minRequestThreshold must be greater than 0");
        }

        if (timeoutMs < 1000) {
            throw new IllegalArgumentException("timeoutMs must be at least 1000ms");
        }

        if (halfOpenMaxRequests < 1) {
            throw new IllegalArgumentException("halfOpenMaxRequests must be greater than 0");
        }

        if (halfOpenSuccessThreshold > halfOpenMaxRequests) {
            throw new IllegalArgumentException("halfOpenSuccessThreshold cannot be greater than halfOpenMaxRequests");
        }

        if (windowSizeMs < 10000) {
            throw new IllegalArgumentException("windowSizeMs must be at least 10 seconds");
        }

        if (bucketCount < 2 || bucketCount > 100) {
            throw new IllegalArgumentException("bucketCount must be between 2 and 100");
        }
    }

    /**
     * 创建默认配置
     */
    public static CircuitBreakerConfig defaultConfig() {
        CircuitBreakerConfig config = new CircuitBreakerConfig();
        config.validate();
        return config;
    }

    /**
     * 创建快速熔断配置（用于测试环境）
     */
    public static CircuitBreakerConfig fastFailConfig() {
        CircuitBreakerConfig config = new CircuitBreakerConfig();
        config.setFailureThresholdPercentage(30);
        config.setMinRequestThreshold(5);
        config.setTimeoutMs(10000);
        config.setHalfOpenMaxRequests(2);
        config.setHalfOpenSuccessThreshold(1);
        config.setWindowSizeMs(30000);
        config.setBucketCount(6);
        config.validate();
        return config;
    }

    /**
     * 创建宽松配置（用于生产环境）
     */
    public static CircuitBreakerConfig lenientConfig() {
        CircuitBreakerConfig config = new CircuitBreakerConfig();
        config.setFailureThresholdPercentage(70);
        config.setMinRequestThreshold(50);
        config.setTimeoutMs(120000);
        config.setHalfOpenMaxRequests(10);
        config.setHalfOpenSuccessThreshold(7);
        config.setWindowSizeMs(120000);
        config.setBucketCount(12);
        config.validate();
        return config;
    }


}
