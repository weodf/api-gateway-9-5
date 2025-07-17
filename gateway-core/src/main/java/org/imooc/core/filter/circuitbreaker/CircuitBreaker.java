package org.imooc.core.filter.circuitbreaker;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 熔断器实现
 * 实现三状态熔断器：CLOSED -> OPEN -> HALF_OPEN -> CLOSED
 */
@Slf4j
public class CircuitBreaker {

    private final CircuitBreakerConfig config;
    private final AtomicReference<CircuitBreakerState> state;
    private final AtomicInteger failureCount;
    private final AtomicInteger successCount;
    private final AtomicInteger requestCount;
    private final AtomicLong lastFailureTime;
    private final AtomicLong stateChangeTime;
    private final AtomicInteger halfOpenRequests;

    // 读写锁，用于状态切换时的线程安全
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final ReentrantReadWriteLock.ReadLock readLock = lock.readLock();
    private final ReentrantReadWriteLock.WriteLock writeLock = lock.writeLock();

    // 滑动窗口统计
    private final SlidingWindow slidingWindow;

    public CircuitBreaker(CircuitBreakerConfig config) {
        this.config = config;
        this.state = new AtomicReference<>(CircuitBreakerState.CLOSED);
        this.failureCount = new AtomicInteger(0);
        this.successCount = new AtomicInteger(0);
        this.requestCount = new AtomicInteger(0);
        this.lastFailureTime = new AtomicLong(0);
        this.stateChangeTime = new AtomicLong(System.currentTimeMillis());
        this.halfOpenRequests = new AtomicInteger(0);
        this.slidingWindow = new SlidingWindow(config.getWindowSizeMs(), config.getBucketCount());

        log.info("CircuitBreaker initialized with config: {}", config);
    }

    /**
     * 判断是否允许请求通过
     */
    public boolean allowRequest() {
        readLock.lock();
        try {
            CircuitBreakerState currentState = state.get();

            switch (currentState) {
                case CLOSED:
                    return true;

                case OPEN:
                    // 检查是否可以进入半开状态
                    if (shouldAttemptReset()) {
                        return attemptStateTransition(CircuitBreakerState.HALF_OPEN);
                    }
                    recordMetric("rejected", "circuit_open");
                    return false;

                case HALF_OPEN:
                    // 半开状态下只允许有限的请求通过
                    int currentHalfOpenRequests = halfOpenRequests.get();
                    if (currentHalfOpenRequests < config.getHalfOpenMaxRequests()) {
                        halfOpenRequests.incrementAndGet();
                        recordMetric("allowed", "half_open");
                        return true;
                    }
                    recordMetric("rejected", "half_open_full");
                    return false;

                default:
                    return false;
            }
        } finally {
            readLock.unlock();
        }
    }

    /**
     * 记录请求结果
     */
    public void recordResult(boolean success, long responseTimeMs) {
        requestCount.incrementAndGet();
        slidingWindow.addSample(success, responseTimeMs);

        readLock.lock();
        CircuitBreakerState currentState;
        try {
             currentState = state.get();
            if (success) {
                handleSuccess(currentState);
            } else {
                handleFailure(currentState);
            }
        } finally {
            readLock.unlock();
        }

        // 记录指标
        recordMetric(success ? "success" : "failure", currentState.name().toLowerCase());
    }

    private void handleSuccess(CircuitBreakerState currentState) {
        int currentSuccessCount = successCount.incrementAndGet();

        switch (currentState) {
            case CLOSED:
                // 关闭状态下的成功，重置失败计数
                failureCount.set(0);
                break;

            case HALF_OPEN:
                // 半开状态下的成功
                log.debug("Success in HALF_OPEN state, count: {}/{}",
                        currentSuccessCount, config.getHalfOpenSuccessThreshold());

                if (currentSuccessCount >= config.getHalfOpenSuccessThreshold()) {
                    // 达到成功阈值，关闭熔断器
                    attemptStateTransition(CircuitBreakerState.CLOSED);
                }
                break;

            case OPEN:
                // 开启状态下不应该有请求通过，这里记录异常情况
                log.warn("Unexpected success in OPEN state");
                break;
        }
    }

    private void handleFailure(CircuitBreakerState currentState) {
        int currentFailureCount = failureCount.incrementAndGet();
        lastFailureTime.set(System.currentTimeMillis());

        switch (currentState) {
            case CLOSED:
                // 检查是否需要打开熔断器
                if (shouldTripCircuit()) {
                    attemptStateTransition(CircuitBreakerState.OPEN);
                }
                break;

            case HALF_OPEN:
                // 半开状态下的失败，立即打开熔断器
                log.debug("Failure in HALF_OPEN state, opening circuit");
                attemptStateTransition(CircuitBreakerState.OPEN);
                break;

            case OPEN:
                // 开启状态下不应该有请求通过
                log.warn("Unexpected failure in OPEN state");
                break;
        }
    }

    /**
     * 检查是否应该打开熔断器
     */
    private boolean shouldTripCircuit() {
        // 检查请求数是否达到最小阈值
        if (requestCount.get() < config.getMinRequestThreshold()) {
            return false;
        }

        // 基于滑动窗口计算失败率
        SlidingWindow.WindowStats stats = slidingWindow.getStats();
        if (stats.getTotalRequests() < config.getMinRequestThreshold()) {
            return false;
        }

        double failureRate = (double) stats.getFailureCount() / stats.getTotalRequests() * 100;
        boolean shouldTrip = failureRate >= config.getFailureThresholdPercentage();

        if (shouldTrip) {
            log.warn("Circuit breaker tripping: failure rate {}% >= threshold {}%, " +
                            "total requests: {}, failures: {}",
                    String.format("%.2f", failureRate),
                    config.getFailureThresholdPercentage(),
                    stats.getTotalRequests(),
                    stats.getFailureCount());
        }

        return shouldTrip;
    }

    /**
     * 检查是否应该尝试重置熔断器状态
     */
    private boolean shouldAttemptReset() {
        long timeSinceOpen = System.currentTimeMillis() - stateChangeTime.get();
        return timeSinceOpen >= config.getTimeoutMs();
    }

    /**
     * 尝试状态转换
     */
    private boolean attemptStateTransition(CircuitBreakerState newState) {
        writeLock.lock();
        try {
            CircuitBreakerState currentState = state.get();

            // 验证状态转换的合法性
            if (!isValidStateTransition(currentState, newState)) {
                log.warn("Invalid state transition from {} to {}", currentState, newState);
                return false;
            }

            // 执行状态转换
            state.set(newState);
            stateChangeTime.set(System.currentTimeMillis());

            // 重置相关计数器
            resetCountersForState(newState);

            log.info("Circuit breaker state changed from {} to {}", currentState, newState);
            recordStateChange(currentState, newState);

            return true;

        } finally {
            writeLock.unlock();
        }
    }

    /**
     * 验证状态转换是否合法
     */
    private boolean isValidStateTransition(CircuitBreakerState from, CircuitBreakerState to) {
        switch (from) {
            case CLOSED:
                return to == CircuitBreakerState.OPEN;
            case OPEN:
                return to == CircuitBreakerState.HALF_OPEN;
            case HALF_OPEN:
                return to == CircuitBreakerState.CLOSED || to == CircuitBreakerState.OPEN;
            default:
                return false;
        }
    }

    /**
     * 根据新状态重置计数器
     */
    private void resetCountersForState(CircuitBreakerState newState) {
        switch (newState) {
            case CLOSED:
                // 关闭状态：重置所有计数器
                failureCount.set(0);
                successCount.set(0);
                requestCount.set(0);
                halfOpenRequests.set(0);
                slidingWindow.reset();
                break;

            case OPEN:
                // 开启状态：保留统计信息，重置半开计数器
                halfOpenRequests.set(0);
                break;

            case HALF_OPEN:
                // 半开状态：重置成功和半开计数器
                successCount.set(0);
                halfOpenRequests.set(0);
                break;
        }
    }

    /**
     * 获取当前熔断器状态
     */
    public CircuitBreakerState getState() {
        return state.get();
    }

    /**
     * 获取熔断器统计信息
     */
    public CircuitBreakerStats getStats() {
        SlidingWindow.WindowStats windowStats = slidingWindow.getStats();

        return CircuitBreakerStats.builder()
                .state(state.get())
                .totalRequests(requestCount.get())
                .failureCount(failureCount.get())
                .successCount(successCount.get())
                .failureRate(windowStats.getFailureRate())
                .averageResponseTime(windowStats.getAverageResponseTime())
                .lastFailureTime(lastFailureTime.get())
                .stateChangeTime(stateChangeTime.get())
                .halfOpenRequests(halfOpenRequests.get())
                .build();
    }

    /**
     * 强制打开熔断器
     */
    public void forceOpen() {
        attemptStateTransition(CircuitBreakerState.OPEN);
        log.info("Circuit breaker forced to OPEN state");
    }

    /**
     * 强制关闭熔断器
     */
    public void forceClose() {
        attemptStateTransition(CircuitBreakerState.CLOSED);
        log.info("Circuit breaker forced to CLOSED state");
    }

    /**
     * 记录指标
     */
    private void recordMetric(String result, String state) {
        try {
            Class<?> agentDelegate = Class.forName("com.appdynamics.agent.api.AgentDelegate");
            Object reporter = agentDelegate.getMethod("getMetricAndEventReporter").invoke(null);

            String metricName = String.format("Gateway|CircuitBreaker|%s|%s", state, result);
            reporter.getClass()
                    .getMethod("reportMetric", String.class, long.class, String.class, int.class)
                    .invoke(reporter, metricName, 1L, "count", 1);

        } catch (Exception e) {
            log.debug("Failed to record circuit breaker metric: {}", e.getMessage());
        }
    }

    /**
     * 记录状态变更
     */
    private void recordStateChange(CircuitBreakerState from, CircuitBreakerState to) {
        try {
            Class<?> agentDelegate = Class.forName("com.appdynamics.agent.api.AgentDelegate");
            Object reporter = agentDelegate.getMethod("getMetricAndEventReporter").invoke(null);

            String eventName = String.format("Circuit breaker state change: %s -> %s", from, to);
            reporter.getClass()
                    .getMethod("reportError", String.class, String.class, int.class)
                    .invoke(reporter, eventName, "STATE_CHANGE", 1);

        } catch (Exception e) {
            log.debug("Failed to record state change event: {}", e.getMessage());
        }
    }
}
