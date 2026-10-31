package com.nox.support;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import java.util.Objects;
import org.slf4j.LoggerFactory;
import org.slf4j.event.KeyValuePair;

/** Captures Logback events of one logger for assertions. Call {@link #close()} after the test. */
public final class CapturedLogs implements AutoCloseable {

    private final Logger logger;
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    private CapturedLogs(String loggerName) {
        this.logger = (Logger) LoggerFactory.getLogger(loggerName);
        appender.start();
        logger.addAppender(appender);
    }

    public static CapturedLogs of(Class<?> loggerOwner) {
        return new CapturedLogs(loggerOwner.getName());
    }

    /** For a logger whose owner is not visible to the test, e.g. a package-private class. */
    public static CapturedLogs of(String loggerName) {
        return new CapturedLogs(loggerName);
    }

    public List<ILoggingEvent> events() {
        return List.copyOf(appender.list);
    }

    public static String keyValue(ILoggingEvent event, String key) {
        List<KeyValuePair> pairs = event.getKeyValuePairs();
        if (pairs == null) {
            return null;
        }
        return pairs.stream()
                .filter(pair -> pair.key.equals(key))
                .map(pair -> Objects.toString(pair.value))
                .findFirst()
                .orElse(null);
    }

    /** Everything the events carry as text: message, arguments, key-values and exceptions. */
    public String allText() {
        StringBuilder text = new StringBuilder();
        for (ILoggingEvent event : appender.list) {
            text.append(event.getFormattedMessage()).append('\n');
            if (event.getKeyValuePairs() != null) {
                event.getKeyValuePairs()
                        .forEach(pair -> text.append(pair.key)
                                .append('=')
                                .append(pair.value)
                                .append('\n'));
            }
            if (event.getThrowableProxy() != null) {
                text.append(ThrowableProxyUtil.asString(event.getThrowableProxy()))
                        .append('\n');
            }
        }
        return text.toString();
    }

    @Override
    public void close() {
        logger.detachAppender(appender);
        appender.stop();
    }
}
