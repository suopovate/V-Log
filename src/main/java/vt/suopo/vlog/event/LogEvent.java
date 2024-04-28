package vt.suopo.vlog.event;


import vt.suopo.vlog.Logger;

/**
 * 日志事件
 *
 * @author suopovate
 * @since 2024/04/27
 * <p>
 * All rights Reserved.
 */
public interface LogEvent {

    Level getLevel();

    String getThreadName();

    Long getEventTime();

    Logger getLogger();

    Throwable getThrowable();

    String getMessage();

    Object[] getArguments();

}
