package io.github.supervate.vlog.appender;

import io.github.supervate.vlog.Lifecycle;

/**
 * Appender,负责日志输出.
 *
 * @author supervate
 * @since 2024/04/27
 * <p>
 * All rights Reserved.
 */
public interface Appender<E> extends Lifecycle {

    /**
     * 在往appender输出日志之前,会先调用support方法,只有支持的事件,才会被通过.
     *
     * @param event 事件
     * @return boolean 是否支持该事件
     * @see AppenderCombiner#append(Object)
     */
    boolean support(E event);

    /**
     * 往appender输出日志
     *
     * @param event 事件
     */
    void append(E event);

}
