package org.imooc.core.filter.circuitbreaker;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.imooc.common.config.Rule;
import org.imooc.common.enums.ResponseCode;
import org.imooc.common.utils.JSONUtil;
import org.imooc.core.context.GatewayContext;
import org.imooc.core.filter.Filter;
import org.imooc.core.filter.FilterAspect;
import org.imooc.core.helper.ResponseHelper;
import org.imooc.core.response.GatewayResponse;

import java.util.Map;
import java.util.concurrent.TimeUnit;

@Slf4j
@FilterAspect(id = "circuit_breaker_filter", name = "熔断器过滤器", order = 95)
public class CircuitBreakerFilter implements Filter {

    private final Cache<String, CircuitBreaker> circuitBreakerCache;

    public CircuitBreakerFilter() {
        this.circuitBreakerCache = Caffeine.newBuilder()
                .maximumSize(1000)
                .expireAfterWrite(1, TimeUnit.HOURS)
                .build();
    }

    @Override
    public void doFilter(GatewayContext ctx) throws Exception {
        Rule.FilterConfig config = ctx.getRule().getFilterConfig("circuit_breaker_filter");
        if (config == null) {
            return;
        }

        CircuitBreakerConfig cbConfig = JSONUtil.parse(config.getConfig(), CircuitBreakerConfig.class);
        String serviceId = ctx.getUniqueId();

        CircuitBreaker circuitBreaker = circuitBreakerCache.get(serviceId,
                k -> new CircuitBreaker(cbConfig));

        // 检查熔断状态
        if (!circuitBreaker.allowRequest()) {
            log.warn("Circuit breaker is OPEN for service: {}", serviceId);

            // 返回降级响应
            GatewayResponse fallbackResponse = getFallbackResponse(ctx, cbConfig);
            ctx.setResponse(fallbackResponse);
            ctx.written();
            ResponseHelper.writeResponse(ctx);
            ctx.terminated();

            recordCircuitBreakerMetric(serviceId, "rejected");
            return;
        }

        // 记录请求开始
        long startTime = System.currentTimeMillis();
        ctx.setAttribute("circuit_breaker.start_time", startTime);
        ctx.setAttribute("circuit_breaker.instance", circuitBreaker);

        recordCircuitBreakerMetric(serviceId, "allowed");
    }

    private GatewayResponse getFallbackResponse(GatewayContext ctx, CircuitBreakerConfig config) {
        if (config.getFallbackResponse() != null) {
            return GatewayResponse.buildGatewayResponse(config.getFallbackResponse());
        }

        return GatewayResponse.buildGatewayResponse(ResponseCode.SERVICE_UNAVAILABLE);
    }

    private void recordCircuitBreakerMetric(String serviceId, String state) {
        try {
            Class<?> agentDelegate = Class.forName("com.appdynamics.agent.api.AgentDelegate");
            Object reporter = agentDelegate.getMethod("getMetricAndEventReporter").invoke(null);

            String metricName = "Gateway|CircuitBreaker|" + serviceId + "|" + state;
            reporter.getClass()
                    .getMethod("reportMetric", String.class, long.class, String.class, int.class)
                    .invoke(reporter, metricName, 1L, "count", 1);

        } catch (Exception e) {
            log.debug("Failed to record circuit breaker metric: {}", e.getMessage());
        }
    }


}
