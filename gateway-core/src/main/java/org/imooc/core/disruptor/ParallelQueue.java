package org.imooc.core.disruptor;

/**
 * @PROJECT_NAME: api-gateway
 * @DESCRIPTION: 多生产者多消费者处理接口
 * @USER: WuYang
 * @DATE: 2023/5/6 23:24
 */
public interface ParallelQueue<E> {

    /**
     * 添加元素
     * @param event
     */
    void add(E event);
    void add(E... event);

    /**
     * 添加多个元素
     * @param event
     * @return
     */
    boolean tryAdd(E event);
    boolean tryAdd(E... event);

    /**
     * 启动
     */
    void start();

    /**
     * 销毁
     */
    void shutDown();
    /**
     * 判断是否已经销毁
     */
    boolean isShutDown();

}
