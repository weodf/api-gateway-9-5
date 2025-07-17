package org.imooc.core.filter.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.google.common.util.concurrent.RateLimiter;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.imooc.common.config.Rule;
import org.imooc.common.enums.ResponseCode;
import org.imooc.common.exception.ResponseException;
import org.imooc.common.utils.JSONUtil;
import org.imooc.core.context.GatewayContext;
import org.imooc.core.filter.Filter;
import org.imooc.core.filter.FilterAspect;

import java.util.concurrent.TimeUnit;

@Slf4j
@FilterAspect(id = "rate_limit_filter", name = "频率限制过滤器", order = 1)
public class RateLimitFilter implements Filter {

    private final Cache<String, RateLimiter> rateLimiterCache;

    public RateLimitFilter() {
        this.rateLimiterCache = Caffeine.newBuilder()
                .maximumSize(10000)
                .expireAfterWrite(1, TimeUnit.HOURS)
                .build();
    }

    @Override
    public void doFilter(GatewayContext ctx) throws Exception {
        Rule.FilterConfig config = ctx.getRule().getFilterConfig("rate_limit_filter");
        if (config == null) {
            return;
        }

        RateLimitConfig rateLimitConfig = JSONUtil.parse(config.getConfig(), RateLimitConfig.class);
        String limitKey = buildLimitKey(ctx, rateLimitConfig);

        RateLimiter rateLimiter = rateLimiterCache.get(limitKey, k ->
                RateLimiter.create(rateLimitConfig.getRequestsPerSecond()));

        if (!rateLimiter.tryAcquire(rateLimitConfig.getTimeoutMs(), TimeUnit.MILLISECONDS)) {
            log.warn("Rate limit exceeded for key: {}", limitKey);
            recordRateLimitMetric(limitKey, "exceeded");
            throw new ResponseException(ResponseCode.SERVICE_UNAVAILABLE);
        }

        recordRateLimitMetric(limitKey, "allowed");
    }

    private String buildLimitKey(GatewayContext ctx, RateLimitConfig config) {
        switch (config.getLimitType()) {
            case "ip":
                return "ip:" + ctx.getRequest().getClientIp();
            case "user":
                return "user:" + ctx.getRequest().getUserId();
            case "api":
                return "api:" + ctx.getRequest().getPath();
            case "service":
                return "service:" + ctx.getUniqueId();
            default:
                return "global";
        }
    }

    private void recordRateLimitMetric(String key, String result) {
        try {
            Class<?> agentDelegate = Class.forName("com.appdynamics.agent.api.AgentDelegate");
            Object reporter = agentDelegate.getMethod("getMetricAndEventReporter").invoke(null);

            String metricName = "Gateway|RateLimit|" + result;
            reporter.getClass()
                    .getMethod("reportMetric", String.class, long.class, String.class, int.class)
                    .invoke(reporter, metricName, 1L, "count", 1);

        } catch (Exception e) {
            log.debug("Failed to record rate limit metric: {}", e.getMessage());
        }
    }

    @Data
    public static class RateLimitConfig {
        private String limitType = "ip"; // ip, user, api, service, global
        private double requestsPerSecond = 100;
        private long timeoutMs = 0;
        private String algorithm = "token_bucket"; // token_bucket, sliding_window
    }
}
