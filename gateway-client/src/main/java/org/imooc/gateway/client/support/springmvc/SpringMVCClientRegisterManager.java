package org.imooc.gateway.client.support.springmvc;

import lombok.extern.slf4j.Slf4j;
import org.imooc.common.config.ServiceDefinition;
import org.imooc.common.config.ServiceInstance;
import org.imooc.common.utils.NetUtils;
import org.imooc.common.utils.TimeUtil;
import org.imooc.gateway.client.core.ApiAnnotationScanner;
import org.imooc.gateway.client.core.ApiProperties;
import org.imooc.gateway.client.support.AbstractClientRegisterManager;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanFactoryUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.web.ServerProperties;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.imooc.common.constants.BasicConst.COLON_SEPARATOR;
import static org.imooc.common.constants.GatewayConst.DEFAULT_WEIGHT;

@Slf4j
public class SpringMVCClientRegisterManager extends AbstractClientRegisterManager implements ApplicationListener<ApplicationEvent>, ApplicationContextAware {
    private ApplicationContext applicationContext;

    @Autowired
    private ServerProperties serverProperties;

    private Set<Object> set = new HashSet<>();

    public SpringMVCClientRegisterManager(ApiProperties apiProperties) {
        super(apiProperties);
    }

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        this.applicationContext = applicationContext;
    }

    @Override
    public void onApplicationEvent(ApplicationEvent applicationEvent) {
        if (applicationEvent instanceof ApplicationStartedEvent) {
            try {
                doRegisterSpringMvc();
            } catch (Exception e) {
                log.error("doRegisterSpringMvc error", e);
                throw new RuntimeException(e);
            }

            log.info("springmvc api started");
        }
    }


    private void doRegisterSpringMvc() {
        Map<String, RequestMappingHandlerMapping> allRequestMappings = BeanFactoryUtils.beansOfTypeIncludingAncestors(applicationContext,
            RequestMappingHandlerMapping.class, true, false);

        for (RequestMappingHandlerMapping handlerMapping : allRequestMappings.values()) {
            Map<RequestMappingInfo, HandlerMethod> handlerMethods = handlerMapping.getHandlerMethods();

            for (Map.Entry<RequestMappingInfo, HandlerMethod> me : handlerMethods.entrySet()) {
                HandlerMethod handlerMethod = me.getValue();
                Class<?> clazz = handlerMethod.getBeanType();

                Object bean = applicationContext.getBean(clazz);

                if (set.contains(bean)) {
                    continue;
                }

                ServiceDefinition serviceDefinition = ApiAnnotationScanner.getInstance().scanner(bean);

                if (serviceDefinition == null) {
                    continue;
                }

                serviceDefinition.setEnvType(getApiProperties().getEnv());

                //服务实例
                ServiceInstance serviceInstance = new ServiceInstance();
                String localIp = NetUtils.getLocalIp();
                int port = serverProperties.getPort();
                String serviceInstanceId = localIp + COLON_SEPARATOR + port;
                String uniqueId = serviceDefinition.getUniqueId();
                String version = serviceDefinition.getVersion();

                serviceInstance.setServiceInstanceId(serviceInstanceId);
                serviceInstance.setUniqueId(uniqueId);
                serviceInstance.setIp(localIp);
                serviceInstance.setPort(port);
                serviceInstance.setRegisterTime(TimeUtil.currentTimeMillis());
                serviceInstance.setVersion(version);
                serviceInstance.setWeight(DEFAULT_WEIGHT);

                if (getApiProperties().isGray()) {
                    serviceInstance.setGray(true);
                }

                //注册
                register(serviceDefinition, serviceInstance);
            }

        }
    }
}
