package org.imooc.core;

import com.lmax.disruptor.*;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;


import java.util.HashMap;
import java.util.Map;

@Data
@Component
@ConfigurationProperties(prefix = "gateway")
public class Config {

    // 基础服务配置
    private int port = 8080;
    private int httpsPort = 8443;
    private int prometheusPort = 18000;
    private String applicationName = "api-gateway";
    private String registryAddress = "nacos-server:8848";
    private String env = "prod";

    // Netty配置
    private int eventLoopGroupBossNum = 1;
    private int eventLoopGroupWokerNum = Runtime.getRuntime().availableProcessors();
    private int maxContentLength = 64 * 1024 * 1024;
    private boolean whenComplete = true;

    // HTTP客户端配置
    private int httpConnectTimeout = 30 * 1000;
    private int httpRequestTimeout = 30 * 1000;
    private int httpMaxRequestRetry = 2;
    private int httpMaxConnections = 10000;
    private int httpConnectionsPerHost = 8000;
    private int httpPooledConnectionIdleTimeout = 60 * 1000;

    // Disruptor配置
    private String bufferType = "parallel";
    private int bufferSize = 1024 * 16;
    private int processThread = Runtime.getRuntime().availableProcessors();
    private String waitStrategy = "blocking";

    // OpenShift配置
    private OpenShiftConfig openshift = new OpenShiftConfig();

    // 安全配置
    private SecurityConfig security = new SecurityConfig();

    // SSL配置
    private SslConfig ssl = new SslConfig();

    // 监控配置
    private MonitoringConfig monitoring = new MonitoringConfig();

    @Data
    public static class OpenShiftConfig {
        private String namespace = "default";
        private String clusterDomain = "svc.cluster.local";
        private boolean serviceDiscoveryEnabled = true;
    }

    @Data
    public static class SecurityConfig {
        private IpConfig ip = new IpConfig();
        private RateLimitConfig rateLimit = new RateLimitConfig();

        @Data
        public static class IpConfig {
            private boolean whitelistEnabled = false;
            private String[] whitelist = {};
            private String[] blacklist = {};
        }

        @Data
        public static class RateLimitConfig {
            private boolean enabled = true;
            private int globalRequestsPerSecond = 1000;
            private int perIpRequestsPerSecond = 100;
        }
    }

    @Data
    public static class SslConfig {
        private boolean enabled = false;
        private String keystorePath;
        private String keystorePassword;
        private String[] protocols = {"TLSv1.2", "TLSv1.3"};
    }

    @Data
    public static class MonitoringConfig {
        private AppDynamicsConfig appdynamics = new AppDynamicsConfig();

        @Data
        public static class AppDynamicsConfig {
            private boolean enabled = true;
            private String applicationName = "API-Gateway";
            private String tierName = "Gateway-Tier";
            private String controllerHost;
            private int controllerPort = 8090;
        }
    }

    public WaitStrategy getWaitStrategy() {
        switch (waitStrategy) {
            case "blocking": return new BlockingWaitStrategy();
            case "busySpin": return new BusySpinWaitStrategy();
            case "yielding": return new YieldingWaitStrategy();
            case "sleeping": return new SleepingWaitStrategy();
            default: return new BlockingWaitStrategy();
        }
    }

    public Map<String, Object> getSpringProperties() {
        Map<String, Object> props = new HashMap<>();
        props.put("server.port", port);
        props.put("management.endpoints.web.exposure.include", "health,info,prometheus");
        props.put("management.endpoint.health.show-details", "always");
        props.put("logging.level.org.imooc.gateway", "INFO");
        return props;
    }
}