package io.github.supervate.vlog.appender;

import java.util.ArrayList;
import java.util.List;

/**
 * appender 组合器
 *
 * @author supervate
 * @since 2024/04/27
 * <p>
 * All rights Reserved.
 */
public class AppenderCombiner<E> extends AbstractAppender<E> {

    volatile private List<Appender<E>> appenderList = new ArrayList<>();

    public AppenderCombiner() {
    }

    public void addAppender(Appender<E> appender) {
        if (appender != null) {
            List<Appender<E>> newAppenderList = new ArrayList<>(appenderList);
            newAppenderList.add(appender);
            appenderList = newAppenderList;
        }
    }

    @Override
    public boolean support(E event) {
        return true;
    }

    @Override
    public void append(E event) {
        if (started()) {
            for (Appender<E> appender : appenderList) {
                if (appender.support(event)) {
                    appender.append(event);
                }
            }
        }
    }

}
