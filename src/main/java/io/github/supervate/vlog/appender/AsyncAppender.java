package io.github.supervate.vlog.appender;


import io.github.supervate.vlog.common.ThrowableUtils;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * 异步appender
 * <p>
 * 注意: doAppend是单独开启一个线程,串行处理所有的event.
 *
 * @author supervate
 * @since 2024/04/27
 * <p>
 * All rights Reserved.
 */
public abstract class AsyncAppender<E> extends AbstractAppender<E> {

    private final BlockingQueue<E> queue;

    public AsyncAppender() {
        this.queue = new LinkedBlockingQueue<>();
    }

    @Override
    public boolean start() {
        new Thread(() -> {
            while (true) {
                try {
                    if (started()) {
                        E event = queue.take();
                        doAppend(event);
                    }
                } catch (Exception e) {
                    System.err.println(ThrowableUtils.throwableToStr(e));
                }
            }
        }).start();
        return super.start();
    }

    @Override
    final public void append(E event) {
        queue.offer(event);
    }

    abstract void doAppend(E event);

}
