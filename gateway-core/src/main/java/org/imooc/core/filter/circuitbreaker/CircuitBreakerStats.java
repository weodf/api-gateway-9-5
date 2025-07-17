package org.imooc.core.filter.circuitbreaker;

import lombok.Builder;
import lombok.Data;

/**
 * 熔断器统计信息
 */
@Data
@Builder
public class CircuitBreakerStats {
    private CircuitBreakerState state;
    private int totalRequests;
    private int failureCount;
    private int successCount;
    private double failureRate;
    private double averageResponseTime;
    private long lastFailureTime;
    private long stateChangeTime;
    private int halfOpenRequests;

    public double getSuccessRate() {
        return totalRequests > 0 ? (double) successCount / totalRequests * 100 : 0;
    }

    public long getTimeSinceLastFailure() {
        return lastFailureTime > 0 ? System.currentTimeMillis() - lastFailureTime : -1;
    }

    public long getTimeSinceStateChange() {
        return System.currentTimeMillis() - stateChangeTime;
    }
}
