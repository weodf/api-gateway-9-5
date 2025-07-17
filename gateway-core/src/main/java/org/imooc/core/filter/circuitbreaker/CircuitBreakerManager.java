package org.imooc.core.filter.circuitbreaker;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.annotation.PreDestroy;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 熔断器管理器
 * 负责创建、管理和监控所有熔断器实例
 */
@Slf4j
@Component
public class CircuitBreakerManager {

    // 熔断器实例缓存
    private final Cache<String, CircuitBreaker> circuitBreakerCache;

    // 配置缓存
    private final Map<String, CircuitBreakerConfig> configCache = new ConcurrentHashMap<>();

    // 定时任务执行器
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);

    public CircuitBreakerManager() {
        this.circuitBreakerCache = Caffeine.newBuilder()
                .maximumSize(1000)
                .expireAfterWrite(1, TimeUnit.HOURS)
                .removalListener((key, value, cause) -> {
                    log.info("Circuit breaker removed: key={}, cause={}", key, cause);
                })
                .build();

        // 启动定时统计任务
        startPeriodicStatsLogging();
    }

    /**
     * 获取或创建熔断器
     */
    public CircuitBreaker getOrCreateCircuitBreaker(String serviceId, CircuitBreakerConfig config) {
        return circuitBreakerCache.get(serviceId, k -> {
            log.info("Creating new circuit breaker for service: {}", serviceId);

            // 验证配置
            config.validate();

            // 缓存配置
            configCache.put(serviceId, config);

            // 创建熔断器实例
            return new CircuitBreaker(config);
        });
    }

    /**
     * 获取熔断器
     */
    public CircuitBreaker getCircuitBreaker(String serviceId) {
        return circuitBreakerCache.getIfPresent(serviceId);
    }

    /**
     * 移除熔断器
     */
    public void removeCircuitBreaker(String serviceId) {
        circuitBreakerCache.invalidate(serviceId);
        configCache.remove(serviceId);
        log.info("Circuit breaker removed for service: {}", serviceId);
    }

    /**
     * 获取所有熔断器统计信息
     */
    public Map<String, CircuitBreakerStats> getAllStats() {
        Map<String, CircuitBreakerStats> allStats = new ConcurrentHashMap<>();

        circuitBreakerCache.asMap().forEach((serviceId, circuitBreaker) -> {
            try {
                allStats.put(serviceId, circuitBreaker.getStats());
            } catch (Exception e) {
                log.warn("Failed to get stats for circuit breaker: {}", serviceId, e);
            }
        });

        return allStats;
    }

    /**
     * 重置所有熔断器
     */
    public void resetAll() {
        circuitBreakerCache.asMap().forEach((serviceId, circuitBreaker) -> {
            try {
                circuitBreaker.forceClose();
                log.info("Reset circuit breaker for service: {}", serviceId);
            } catch (Exception e) {
                log.error("Failed to reset circuit breaker for service: {}", serviceId, e);
            }
        });
    }

    /**
     * 强制打开指定服务的熔断器
     */
    public boolean forceOpen(String serviceId) {
        CircuitBreaker circuitBreaker = circuitBreakerCache.getIfPresent(serviceId);
        if (circuitBreaker != null) {
            circuitBreaker.forceOpen();
            log.info("Forced open circuit breaker for service: {}", serviceId);
            return true;
        }
        return false;
    }

    /**
     * 强制关闭指定服务的熔断器
     */
    public boolean forceClose(String serviceId) {
        CircuitBreaker circuitBreaker = circuitBreakerCache.getIfPresent(serviceId);
        if (circuitBreaker != null) {
            circuitBreaker.forceClose();
            log.info("Forced close circuit breaker for service: {}", serviceId);
            return true;
        }
        return false;
    }

    /**
     * 更新熔断器配置
     */
    public boolean updateConfig(String serviceId, CircuitBreakerConfig newConfig) {
        try {
            newConfig.validate();

            // 移除旧的熔断器
            removeCircuitBreaker(serviceId);

            // 创建新的熔断器
            getOrCreateCircuitBreaker(serviceId, newConfig);

            log.info("Updated circuit breaker config for service: {}", serviceId);
            return true;

        } catch (Exception e) {
            log.error("Failed to update circuit breaker config for service: {}", serviceId, e);
            return false;
        }
    }

    /**
     * 获取缓存统计信息
     */
    public Map<String, Object> getCacheStats() {
        var stats = circuitBreakerCache.stats();
        Map<String, Object> cacheStats = new ConcurrentHashMap<>();

        cacheStats.put("size", circuitBreakerCache.estimatedSize());
        cacheStats.put("hitCount", stats.hitCount());
        cacheStats.put("missCount", stats.missCount());
        cacheStats.put("hitRate", stats.hitRate());
        cacheStats.put("evictionCount", stats.evictionCount());
        cacheStats.put("averageLoadPenalty", stats.averageLoadPenalty());

        return cacheStats;
    }

    /**
     * 启动定时统计日志
     */
    private void startPeriodicStatsLogging() {
        scheduler.scheduleWithFixedDelay(() -> {
            try {
                logCircuitBreakerStats();
            } catch (Exception e) {
                log.error("Error in periodic stats logging", e);
            }
        }, 60, 60, TimeUnit.SECONDS);
    }

    /**
     * 记录熔断器统计日志
     */
    private void logCircuitBreakerStats() {
        Map<String, CircuitBreakerStats> allStats = getAllStats();

        if (allStats.isEmpty()) {
            return;
        }

        log.info("=== Circuit Breaker Statistics ===");

        allStats.forEach((serviceId, stats) -> {
            log.info("Service: {}, State: {}, Total: {}, Failures: {}, " +
                            "Failure Rate: {:.2f}%, Avg Response: {:.2f}ms",
                    serviceId,
                    stats.getState(),
                    stats.getTotalRequests(),
                    stats.getFailureCount(),
                    stats.getFailureRate(),
                    stats.getAverageResponseTime());
        });

        // 记录缓存统计
        Map<String, Object> cacheStats = getCacheStats();
        log.info("Cache Stats: size={}, hitRate={:.2f}%, evictions={}",
                cacheStats.get("size"),
                (Double)cacheStats.get("hitRate") * 100,
                cacheStats.get("evictionCount"));
    }

    @PreDestroy
    public void shutdown() {
        log.info("Shutting down circuit breaker manager...");

        try {
            scheduler.shutdown();
            if (!scheduler.awaitTermination(10, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }

        // 清理所有熔断器
        circuitBreakerCache.invalidateAll();
        configCache.clear();

        log.info("Circuit breaker manager shutdown completed");
    }
}

