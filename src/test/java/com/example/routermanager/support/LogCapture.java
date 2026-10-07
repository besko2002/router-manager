package com.example.routermanager.support;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.stream.Collectors;

/** Captures everything logged while it is open, so a test can assert the password never appears. */
public final class LogCapture implements AutoCloseable {

    private final ch.qos.logback.classic.Logger root;
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private final Level previousLevel;

    private LogCapture() {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        root = context.getLogger(ch.qos.logback.classic.Logger.ROOT_LOGGER_NAME);
        previousLevel = root.getLevel();
        root.setLevel(Level.TRACE);
        appender.setContext(context);
        appender.start();
        root.addAppender(appender);
    }

    public static LogCapture start() {
        return new LogCapture();
    }

    /** Every captured message with its arguments already substituted, plus stack traces. */
    public String text() {
        return appender.list.stream()
                .map(event -> {
                    StringBuilder line = new StringBuilder(event.getFormattedMessage());
                    var proxy = event.getThrowableProxy();
                    while (proxy != null) {
                        line.append(' ').append(proxy.getClassName()).append(':')
                                .append(proxy.getMessage());
                        proxy = proxy.getCause();
                    }
                    return line.toString();
                })
                .collect(Collectors.joining("\n"));
    }

    public List<ILoggingEvent> events() {
        return List.copyOf(appender.list);
    }

    @Override
    public void close() {
        root.detachAppender(appender);
        appender.stop();
        root.setLevel(previousLevel);
    }
}
