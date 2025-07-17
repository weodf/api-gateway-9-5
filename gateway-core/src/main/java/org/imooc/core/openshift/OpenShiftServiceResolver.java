package org.imooc.core.openshift;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

@Component
@Slf4j
public class OpenShiftServiceResolver {

    @Value("${gateway.openshift.namespace:default}")
    private String namespace;

    @Value("${gateway.openshift.cluster-domain:svc.cluster.local}")
    private String clusterDomain;

    private final Map<String, String> serviceCache = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        log.info("OpenShift ServiceResolver initialized: namespace={}, domain={}",
                namespace, clusterDomain);
    }

    /**
     * 解析服务名到OpenShift内部URL
     */
    public String resolveServiceUrl(String serviceName, int port) {
        String cacheKey = serviceName + ":" + port;
        return serviceCache.computeIfAbsent(cacheKey, k -> {
            String serviceHost = String.format("%s.%s.%s", serviceName, namespace, clusterDomain);
            String serviceUrl = String.format("http://%s:%d", serviceHost, port);
            log.debug("Resolved service: {} -> {}", serviceName, serviceUrl);
            return serviceUrl;
        });
    }

    public String resolveServiceUrl(String serviceName) {
        return resolveServiceUrl(serviceName, 8080);
    }

    /**
     * 从uniqueId解析服务名
     * uniqueId格式: "service-name:version" -> "service-name"
     */
    public String extractServiceName(String uniqueId) {
        if (uniqueId.contains(":")) {
            return uniqueId.split(":")[0];
        }
        return uniqueId;
    }

    /**
     * 检查服务是否可达
     */
    public boolean isServiceAvailable(String serviceName) {
        try {
            String serviceUrl = resolveServiceUrl(serviceName);
            // 这里可以实现简单的连通性检查
            // 例如TCP连接测试或HTTP健康检查
            return true; // 简化实现
        } catch (Exception e) {
            log.warn("Service {} is not available: {}", serviceName, e.getMessage());
            return false;
        }
    }
}