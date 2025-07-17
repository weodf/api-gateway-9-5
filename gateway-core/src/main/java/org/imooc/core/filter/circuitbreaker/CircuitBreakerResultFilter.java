import lombok.extern.slf4j.Slf4j;
import org.imooc.core.context.GatewayContext;
import org.imooc.core.filter.Filter;
import org.imooc.core.filter.FilterAspect;
import org.imooc.core.filter.circuitbreaker.CircuitBreaker;
import org.imooc.core.filter.circuitbreaker.CircuitBreakerState;
import org.imooc.core.filter.circuitbreaker.CircuitBreakerStats;

import java.util.Map;

/**
 * 熔断器结果处理过滤器
 * 在请求处理完成后记录结果到熔断器
 */
@Slf4j
@FilterAspect(id = "circuit_breaker_result_filter",
        name = "熔断器结果处理过滤器",
        order = Integer.MAX_VALUE - 1)
public class CircuitBreakerResultFilter implements Filter {

    @Override
    public void doFilter(GatewayContext ctx) throws Exception {
        // 获取熔断器实例和开始时间
        CircuitBreaker circuitBreaker = (CircuitBreaker) ctx.getAttribute(
                Map.of("circuit_breaker.instance", ""));
        Long startTime = (Long) ctx.getAttribute(
                Map.of("circuit_breaker.start_time", ""));

        if (circuitBreaker == null || startTime == null) {
            // 没有熔断器配置，直接返回
            return;
        }

        // 计算响应时间
        long responseTime = System.currentTimeMillis() - startTime;

        // 判断请求是否成功
        boolean success = isRequestSuccessful(ctx);

        // 记录结果到熔断器
        circuitBreaker.recordResult(success, responseTime);

        // 记录详细日志
        logRequestResult(ctx, success, responseTime, circuitBreaker);

        // 记录APM指标
        recordApmMetrics(ctx, success, responseTime, circuitBreaker);
    }

    /**
     * 判断请求是否成功
     */
    private boolean isRequestSuccessful(GatewayContext ctx) {
        if (ctx.getThrowable() != null) {
            // 有异常，认为失败
            return false;
        }

        if (ctx.getResponse() == null) {
            // 没有响应，认为失败
            return false;
        }

        int statusCode = ctx.getResponse().getHttpResponseStatus().code();

        // 5xx错误认为是服务端错误，需要熔断
        // 4xx错误认为是客户端错误，不需要熔断
        return statusCode < 500;
    }

    /**
     * 记录请求结果日志
     */
    private void logRequestResult(GatewayContext ctx, boolean success, long responseTime,
                                  CircuitBreaker circuitBreaker) {
        String serviceId = ctx.getUniqueId();
        CircuitBreakerState state = circuitBreaker.getState();

        if (success) {
            log.debug("Circuit breaker recorded SUCCESS for service {}, " +
                            "response time: {}ms, state: {}",
                    serviceId, responseTime, state);
        } else {
            int statusCode = ctx.getResponse() != null ?
                    ctx.getResponse().getHttpResponseStatus().code() : -1;
            String error = ctx.getThrowable() != null ?
                    ctx.getThrowable().getMessage() : "Unknown error";

            log.warn("Circuit breaker recorded FAILURE for service {}, " +
                            "status: {}, error: {}, response time: {}ms, state: {}",
                    serviceId, statusCode, error, responseTime, state);
        }

        // 如果状态发生了变化，记录详细信息
        CircuitBreakerStats stats = circuitBreaker.getStats();
        if (log.isDebugEnabled()) {
            log.debug("Circuit breaker stats for {}: total={}, failures={}, " +
                            "failure rate={:.2f}%, state={}",
                    serviceId, stats.getTotalRequests(), stats.getFailureCount(),
                    stats.getFailureRate(), stats.getState());
        }
    }

    /**
     * 记录APM指标
     */
    private void recordApmMetrics(GatewayContext ctx, boolean success, long responseTime,
                                  CircuitBreaker circuitBreaker) {
        String serviceId = ctx.getUniqueId();
        CircuitBreakerState state = circuitBreaker.getState();
        String result = success ? "success" : "failure";

        try {
            Class<?> agentDelegate = Class.forName("com.appdynamics.agent.api.AgentDelegate");
            Object reporter = agentDelegate.getMethod("getMetricAndEventReporter").invoke(null);

            // 记录请求结果指标
            String resultMetric = String.format("Gateway|CircuitBreaker|%s|%s", serviceId, result);
            reporter.getClass()
                    .getMethod("reportMetric", String.class, long.class, String.class, int.class)
                    .invoke(reporter, resultMetric, 1L, "count", 1);

            // 记录响应时间指标
            String responseTimeMetric = String.format("Gateway|CircuitBreaker|%s|ResponseTime", serviceId);
            reporter.getClass()
                    .getMethod("reportMetric", String.class, long.class, String.class, int.class)
                    .invoke(reporter, responseTimeMetric, responseTime, "ms", 1);

            // 记录熔断器状态指标
            String stateMetric = String.format("Gateway|CircuitBreaker|%s|State|%s", serviceId, state.name());
            reporter.getClass()
                    .getMethod("reportMetric", String.class, long.class, String.class, int.class)
                    .invoke(reporter, stateMetric, 1L, "count", 1);

            // 记录统计信息
            CircuitBreakerStats stats = circuitBreaker.getStats();

            String failureRateMetric = String.format("Gateway|CircuitBreaker|%s|FailureRate", serviceId);
            reporter.getClass()
                    .getMethod("reportMetric", String.class, long.class, String.class, int.class)
                    .invoke(reporter, failureRateMetric, (long)(stats.getFailureRate() * 100), "percentage", 1);

            String totalRequestsMetric = String.format("Gateway|CircuitBreaker|%s|TotalRequests", serviceId);
            reporter.getClass()
                    .getMethod("reportMetric", String.class, long.class, String.class, int.class)
                    .invoke(reporter, totalRequestsMetric, (long)stats.getTotalRequests(), "count", 1);

        } catch (Exception e) {
            log.debug("Failed to record circuit breaker APM metrics: {}", e.getMessage());
        }
    }
}