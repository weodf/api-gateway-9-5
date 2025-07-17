package org.imooc.core.filter.security;


import lombok.extern.slf4j.Slf4j;
import org.imooc.common.enums.ResponseCode;
import org.imooc.common.exception.ResponseException;
import org.imooc.core.Config;
import org.imooc.core.context.GatewayContext;
import org.imooc.core.filter.Filter;
import org.imooc.core.filter.FilterAspect;
import org.springframework.beans.factory.annotation.Autowired;

import java.net.InetAddress;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

@Slf4j
@FilterAspect(id = "ip_security_filter", name = "IP安全过滤器", order = -10)
public class IpSecurityFilter implements Filter {

    @Autowired
    private Config config;

    private Set<String> whitelistSet;
    private Set<String> blacklistSet;

    @Override
    public void doFilter(GatewayContext ctx) throws Exception {
        Config.SecurityConfig.IpConfig ipConfig = config.getSecurity().getIp();

        // 初始化IP集合
        initIpSets(ipConfig);

        String clientIp = ctx.getRequest().getClientIp();

        // 检查黑名单
        if (blacklistSet.contains(clientIp) || isInIpRange(clientIp, ipConfig.getBlacklist())) {
            log.warn("Request blocked - IP in blacklist: {}", clientIp);
            recordSecurityMetric("blacklist_blocked", clientIp);
            throw new ResponseException(ResponseCode.BLACKLIST);
        }

        // 检查白名单（如果启用）
        if (ipConfig.isWhitelistEnabled()) {
            if (!whitelistSet.contains(clientIp) && !isInIpRange(clientIp, ipConfig.getWhitelist())) {
                log.warn("Request blocked - IP not in whitelist: {}", clientIp);
                recordSecurityMetric("whitelist_blocked", clientIp);
                throw new ResponseException(ResponseCode.WHITELIST);
            }
        }

        log.debug("IP security check passed for: {}", clientIp);
        recordSecurityMetric("ip_allowed", clientIp);
    }

    private void initIpSets(Config.SecurityConfig.IpConfig ipConfig) {
        if (whitelistSet == null) {
            whitelistSet = new HashSet<>(Arrays.asList(ipConfig.getWhitelist()));
        }
        if (blacklistSet == null) {
            blacklistSet = new HashSet<>(Arrays.asList(ipConfig.getBlacklist()));
        }
    }

    private boolean isInIpRange(String clientIp, String[] ipRanges) {
        // 简化的CIDR检查实现
        for (String range : ipRanges) {
            if (range.contains("/")) {
                // CIDR格式检查
                if (isIpInCidr(clientIp, range)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isIpInCidr(String ip, String cidr) {
        try {
            String[] parts = cidr.split("/");
            InetAddress targetAddr = InetAddress.getByName(ip);
            InetAddress rangeAddr = InetAddress.getByName(parts[0]);
            int prefixLength = Integer.parseInt(parts[1]);

            byte[] targetBytes = targetAddr.getAddress();
            byte[] rangeBytes = rangeAddr.getAddress();

            int bytesToCheck = prefixLength / 8;
            int bitsToCheck = prefixLength % 8;

            // 检查完整字节
            for (int i = 0; i < bytesToCheck; i++) {
                if (targetBytes[i] != rangeBytes[i]) {
                    return false;
                }
            }

            // 检查剩余位
            if (bitsToCheck > 0) {
                int mask = 0xFF << (8 - bitsToCheck);
                return (targetBytes[bytesToCheck] & mask) == (rangeBytes[bytesToCheck] & mask);
            }

            return true;
        } catch (Exception e) {
            log.warn("Error checking CIDR {} for IP {}: {}", cidr, ip, e.getMessage());
            return false;
        }
    }

    private void recordSecurityMetric(String action, String clientIp) {
        try {
            Class<?> agentDelegate = Class.forName("com.appdynamics.agent.api.AgentDelegate");
            Object reporter = agentDelegate.getMethod("getMetricAndEventReporter").invoke(null);

            String metricName = "Gateway|Security|IP|" + action;
            reporter.getClass()
                    .getMethod("reportMetric", String.class, long.class, String.class, int.class)
                    .invoke(reporter, metricName, 1L, "count", 1);

        } catch (Exception e) {
            log.debug("Failed to record security metric: {}", e.getMessage());
        }
    }
}