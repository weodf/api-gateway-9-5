package org.imooc.core.filter.circuitbreaker;

/**
        * 熔断器状态枚举
        */
public enum CircuitBreakerState {
    /**
     * 关闭状态：正常处理请求
     */
    CLOSED,

    /**
     * 开启状态：拒绝所有请求
     */
    OPEN,

    /**
     * 半开状态：允许少量请求进行测试
     */
    HALF_OPEN
}